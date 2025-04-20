package org.rucca.snake.worker.infra.amqp

import com.rabbitmq.client.Channel
import com.rabbitmq.client.GetResponse
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import org.rucca.snake.worker.config.ApplicationConfig
import org.slf4j.LoggerFactory
import org.springframework.amqp.core.Message
import org.springframework.amqp.rabbit.connection.Connection
import org.springframework.amqp.rabbit.connection.ConnectionFactory
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.amqp.rabbit.support.MessagePropertiesConverter
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

data class Delivery(
    val message: Message,
    val channel: Channel, // The specific channel this message was received on
    val deliveryTag: Long
)

@Component
class TaskPoller(
    private val messagePropertiesConverter: MessagePropertiesConverter, // Inject the converter specifically
    private val taskProcessor: TaskProcessor,
    private val connectionFactory: ConnectionFactory,
    private val applicationConfig: ApplicationConfig
) : DisposableBean {

    private val logger = LoggerFactory.getLogger(TaskPoller::class.java)

    @Value("\${amqp.queue.compile}")
    private lateinit var compileQueueName: String

    @Value("\${amqp.queue.execute}")
    private lateinit var executeQueueName: String

    // Timeout is not used for basicGet, but keep for potential future use or clarity
    // @Value("\${application.polling.receive-timeout-ms:100}")
    // private var receiveTimeoutMs: Long = 100

    private val pollingActive = AtomicBoolean(false)
    // Still use SupervisorJob, Dispatchers.IO is suitable for blocking channel ops
    private val pollingScope = CoroutineScope(Dispatchers.IO + SupervisorJob() + CoroutineName("TaskPollerScope"))
    private val activeJobs = AtomicInteger(0)
    private val maxConcurrentJobs: Int by lazy { applicationConfig.concurrency.maxWorkerJobs }


    @EventListener(ApplicationReadyEvent::class)
    fun startPolling() {
        if (pollingActive.compareAndSet(false, true)) {
            logger.info(
                "Application ready. Max concurrent jobs: {}. Starting task polling for queues: '{}', '{}'",
                maxConcurrentJobs, compileQueueName, executeQueueName
            )
            launchPollingLoop(compileQueueName)
            launchPollingLoop(executeQueueName)
        } else {
            logger.warn("Polling already active, ignoring ApplicationReadyEvent signal.")
        }
    }

    private fun launchPollingLoop(queueName: String) {
        pollingScope.launch {
            logger.info("Polling loop started for queue: {}", queueName)
            while (pollingActive.get() && isActive) { // Check both flags
                var delivery: Delivery? = null // To hold result from pollQueue
                try {
                    // Optional: Connection check (less critical now as pollQueue handles channel creation)
                    // if (!isConnectionOpen()) { ... delay ... continue }

                    // Concurrency Check
                    if (activeJobs.get() >= maxConcurrentJobs) {
                        logger.trace("Worker reached max concurrency ({}/{}), pausing poll for queue {}",
                            activeJobs.get(), maxConcurrentJobs, queueName)
                        delay(applicationConfig.polling.busyDelayMs)
                        continue
                    }

                    // --- Poll using the new direct channel method ---
                    delivery = pollQueueUsingChannel(queueName)

                    if (delivery != null) {
                        val jobId = delivery.message.messageProperties.correlationId ?: "[unknown_jobId-${delivery.deliveryTag}]"
                        activeJobs.incrementAndGet() // Increment before launching processor

                        // Launch processing coroutine, passing the Delivery object
                        launch(CoroutineName("TaskProcessor-$jobId")) {
                            var processedSuccessfully = false
                            try {
                                logger.info(
                                    "Processing message for jobId: {} (deliveryTag: {}) from queue: {}",
                                    jobId, delivery.deliveryTag, queueName
                                )
                                taskProcessor.processMessage(delivery.message)
                                processedSuccessfully = true // Mark success if processMessage doesn't throw
                                logger.info("Successfully processed message for jobId: {}", jobId)
                            } catch (e: Exception) {
                                logger.error(
                                    "Error processing message for jobId: {} from queue: {}. Error: {}",
                                    jobId, queueName, e.message
                                    // Log stack trace if needed: , e
                                )
                                // processedSuccessfully remains false
                            } finally {
                                val countAfter = activeJobs.decrementAndGet()
                                logger.debug("Decremented active jobs to {}, finished processing for {}", countAfter, jobId)

                                // --- ACK/NACK on the SAME channel used for basicGet ---
                                // Use withContext for IO operation, ensure channel closure even on error/cancellation
                                withContext(Dispatchers.IO + NonCancellable) { // Use NonCancellable for critical cleanup
                                    try {
                                        if (processedSuccessfully) {
                                            delivery.channel.basicAck(delivery.deliveryTag, false) // multiple = false
                                            logger.debug("ACKed message for jobId: {} (deliveryTag: {}) on channel {}", jobId, delivery.deliveryTag, delivery.channel.channelNumber)
                                        } else {
                                            delivery.channel.basicNack(delivery.deliveryTag, false, false) // multiple = false, requeue = false
                                            logger.warn("NACKed message for jobId: {} (deliveryTag: {}) on channel {} (requeue=false)", jobId, delivery.deliveryTag, delivery.channel.channelNumber)
                                        }
                                    } catch (ioe: IOException) {
                                        logger.error("CRITICAL: IOException during basicAck/basicNack for deliveryTag {} on channel {}: {}", delivery.deliveryTag, delivery.channel.channelNumber, ioe.message, ioe)
                                        // Channel might be closed already, cannot do much more
                                    } catch (e: Exception) {
                                        logger.error("CRITICAL: Exception during basicAck/basicNack for deliveryTag {} on channel {}: {}", delivery.deliveryTag, delivery.channel.channelNumber, e.message, e)
                                    } finally {
                                        // --- Close the channel ---
                                        try {
                                            if (delivery.channel.isOpen) {
                                                delivery.channel.close()
                                                logger.debug("Closed channel {} after processing deliveryTag {}", delivery.channel.channelNumber, delivery.deliveryTag)
                                            }
                                        } catch (closeEx: Exception) {
                                            logger.error("Error closing channel {} for deliveryTag {}: {}", delivery.channel.channelNumber, delivery.deliveryTag, closeEx.message)
                                        }
                                    }
                                } // End withContext for ACK/NACK/Close
                            } // End finally block
                        } // End processing coroutine launch
                    } else {
                        // No message received, wait for idle delay
                        delay(applicationConfig.polling.idleDelayMs)
                    }

                } catch (e: CancellationException) {
                    logger.info("Polling loop for queue {} cancelled.", queueName)
                    // Ensure channel associated with a potentially fetched but unprocessed message is closed
                    delivery?.let { d -> closeChannelSafe(d.channel, d.deliveryTag, "Polling loop cancelled") }
                    break // Exit loop
                } catch (e: Exception) {
                    // Catch errors during the pollQueueUsingChannel call itself (e.g., connection issues)
                    logger.error("Error in polling loop for queue {}: {}. Retrying after delay.", queueName, e.message, e)
                    // Ensure channel associated with a potentially fetched but unprocessed message is closed
                    delivery?.let { d -> closeChannelSafe(d.channel, d.deliveryTag, "Polling loop error") }
                    delay(5.seconds.inWholeMilliseconds) // Wait longer
                }
            } // End while loop
            logger.info("Polling loop stopped for queue: {}", queueName)
        } // End pollingScope.launch
    }

    /**
     * Polls a single message using a dedicated channel.
     * Creates a connection and channel, performs basicGet(autoAck=false),
     * converts the result to a Spring AMQP Message, and packages it with the channel and tag.
     * The channel MUST be closed by the caller after ACK/NACK.
     * Returns null if no message is available or an error occurs during fetch.
     */
    private suspend fun pollQueueUsingChannel(queueName: String): Delivery? {
        // This involves creating a new connection and channel for each poll attempt.
        // While simple, this can be inefficient. Consider managing a pool of channels
        // if performance becomes an issue, but that adds complexity.
        // Let's stick to the expert's direct suggestion first.
        var connection: Connection? = null
        var channel: Channel? = null
        return try {
            withContext(Dispatchers.IO) { // Perform blocking operations on IO dispatcher
                // 1. Create Connection and Channel (handle potential errors)
                connection = connectionFactory.createConnection()
                channel = connection!!.createChannel(false) // false for non-transactional channel

                if (channel == null || !channel!!.isOpen) {
                    logger.error("Failed to create or open channel for polling queue {}", queueName)
                    closeChannelSafe(channel) // Attempt cleanup
                    closeConnectionSafe(connection)
                    return@withContext null
                }
                val currentChannel = channel!! // Now non-null

                // 2. Basic Get (autoAck = false)
                val response: GetResponse? = try {
                    currentChannel.basicGet(queueName, false)
                } catch (e: Exception) {
                    logger.error("Exception during basicGet for queue {}: {}", queueName, e.message)
                    null // Treat as no message on error
                }


                if (response == null) {
                    // No message available, close channel and connection
                    closeChannelSafe(currentChannel)
                    closeConnectionSafe(connection)
                    return@withContext null // Return null when no message
                }

                // 3. Convert to Spring AMQP Message
                // We need a MessagePropertiesConverter bean injected.
                val properties = messagePropertiesConverter.toMessageProperties(
                    response.props, response.envelope, "UTF-8" // Assuming UTF-8 encoding
                )
                // Set crucial properties not mapped automatically
                properties.deliveryTag = response.envelope.deliveryTag
                properties.consumerQueue = queueName
                // Add others if needed (messageId, correlationId etc. are usually mapped from response.props)


                val message = Message(response.body, properties)

                // 4. Package into Delivery object - DO NOT CLOSE CHANNEL HERE
                logger.debug("Polled message deliveryTag {} from queue {} on channel {}", response.envelope.deliveryTag, queueName, currentChannel.channelNumber)
                Delivery(message, currentChannel, response.envelope.deliveryTag)

                // Note: Connection is NOT closed here. It will be closed implicitly when the channel is closed
                // IF using CachingConnectionFactory with channel caching disabled OR if channel is closed properly.
                // Explicit connection closing might be safer if not using CachingConnectionFactory heavily.
                // For now, rely on channel closure to signal potential connection return to pool/closure.
            }
        } catch (e: Exception) {
            // Catch errors during connection/channel creation or message conversion
            logger.error("Error during polling attempt for queue {}: {}", queueName, e.message, e)
            closeChannelSafe(channel) // Attempt cleanup
            closeConnectionSafe(connection)
            null // Return null on error
        }
    }

    /** Safely closes a channel, logging errors */
    private fun closeChannelSafe(channel: Channel?, deliveryTag: Long? = null, reason: String = "") {
        if (channel != null && channel.isOpen) {
            try {
                channel.close()
                logger.debug("Safely closed channel {} (reason: {})", channel.channelNumber, reason.ifEmpty { "normal cleanup" })
            } catch (e: Exception) {
                logger.error("Error closing channel {} for deliveryTag {}: {}", channel.channelNumber, deliveryTag ?: "N/A", e.message)
            }
        }
    }

    /** Safely closes a connection, logging errors */
    private fun closeConnectionSafe(connection: Connection?) {
        if (connection != null && connection.isOpen) {
            try {
                connection.close()
                logger.debug("Safely closed connection {}", connection.delegate)
            } catch (e: Exception) {
                logger.error("Error closing connection {}: {}", connection.delegate, e.message)
            }
        }
    }

    override fun destroy() {
        logger.info("Application shutting down. Stopping task polling...")
        if (pollingActive.compareAndSet(true, false)) {
            pollingScope.cancel("Application shutdown")
            logger.info("Task polling scope cancelled.")
        } else {
            logger.info("Polling was already inactive.")
        }
    }
}