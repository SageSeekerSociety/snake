package org.rucca.snake.controller.infra.amqp

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.rabbitmq.client.Channel
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import java.util.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.rucca.snake.common.constants.AmqpConstants
import org.rucca.snake.common.domain.model.CompilationResultNotification
import org.rucca.snake.common.domain.model.ExecutionResultNotification
import org.rucca.snake.common.domain.model.JobStatus
import org.rucca.snake.controller.domain.model.JobSseEvent
import org.rucca.snake.controller.domain.service.JobFlowService
import org.rucca.snake.controller.domain.service.JobQueryService
import org.rucca.snake.controller.domain.service.PlayerUpdateService
import org.slf4j.LoggerFactory
import org.springframework.amqp.core.AcknowledgeMode
import org.springframework.amqp.core.Message
import org.springframework.amqp.rabbit.connection.ConnectionFactory
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * Listens for job results from RabbitMQ and processes them asynchronously. This implementation uses
 * a reactive pattern with Kotlin Coroutines to achieve non-blocking, concurrent message processing
 * with safe manual acknowledgment.
 */
@Component
class ResultListener(
    private val objectMapper: ObjectMapper,
    private val playerUpdateService: PlayerUpdateService,
    private val jobFlowService: JobFlowService,
    private val jobQueryService: JobQueryService,
    private val connectionFactory: ConnectionFactory,
    @Value("\${amqp.queue.results:oj.results.notify}") private val queueName: String,
) {
    private val logger = LoggerFactory.getLogger(ResultListener::class.java)

    private val scope =
        CoroutineScope(Dispatchers.Default + SupervisorJob() + CoroutineName("ResultListenerScope"))
    private var listenerContainer: SimpleMessageListenerContainer? = null

    /** Initializes and starts the programmatic RabbitMQ listener upon bean creation. */
    @PostConstruct
    fun startListener() {
        val container = SimpleMessageListenerContainer(connectionFactory)
        container.setQueueNames(queueName)
        container.acknowledgeMode = AcknowledgeMode.MANUAL
        container.setPrefetchCount(10) // Adjust based on processing capacity
        container.setDefaultRequeueRejected(false)

        // Creates a Kotlin Flow from the callback-based message listener.
        val messageFlow =
            callbackFlow<Pair<Message, Channel>> {
                val listener = ChannelAwareMessageListener { message, channel ->
                    trySend(message to channel!!)
                }
                container.setMessageListener(listener)

                container.start()
                logger.info("Reactive RabbitMQ listener started for queue: [{}]", queueName)

                // Defines cleanup logic for when the consuming coroutine is cancelled.
                awaitClose {
                    logger.info("Stopping RabbitMQ listener container for queue: [{}]", queueName)
                    container.stop()
                }
            }

        // Launches a long-running coroutine to consume and process messages from the flow.
        messageFlow
            .onEach { (message, channel) -> // Process each message concurrently.
                val deliveryTag = message.messageProperties.deliveryTag
                val correlationId = message.messageProperties.correlationId
                try {
                    processMessageInternal(message)
                    channel.basicAck(deliveryTag, false) // `false` for single message ack.
                    logger.debug(
                        "Successfully processed and ACKed message for job {}",
                        correlationId,
                    )
                } catch (e: Exception) {
                    logger.error(
                        "Failed to process message for job {}. NACKing. Error: {}",
                        correlationId,
                        e.message,
                        e,
                    )
                    // `requeue=false`: discard message or route to Dead Letter Queue if configured.
                    channel.basicNack(deliveryTag, false, false)
                }
            }
            .launchIn(scope)

        this.listenerContainer = container
    }

    /**
     * Contains the core business logic for processing a single message.
     *
     * @throws Exception if processing fails, allowing the caller to NACK the message.
     */
    private suspend fun processMessageInternal(message: Message) {
        val messageBody = String(message.body, Charsets.UTF_8)
        val correlationId = message.messageProperties.correlationId
        val messageType =
            message.messageProperties.headers[AmqpConstants.HEADER_MESSAGE_TYPE] as? String

        if (correlationId.isNullOrBlank() || messageType.isNullOrBlank()) {
            throw IllegalArgumentException(
                "Message is invalid: missing correlationId or messageType."
            )
        }

        val jobUUID =
            try {
                UUID.fromString(correlationId)
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("Invalid job ID format: $correlationId", e)
            }

        logger.info(
            "Processing result notification - JobId: {}, Type: {}",
            correlationId,
            messageType,
        )

        // 1. Fetch the authoritative, persisted job data from the database.
        val finalResultDto =
            jobQueryService.getJobStatusAndResult(jobUUID)
                ?: throw RuntimeException("Job record not found in DB for job ID: $jobUUID")

        // 2. Convert the DTO to a mutable map to serve as the base for our SSE event data.
        val baseEventData: MutableMap<String, Any?> =
            objectMapper.convertValue(
                finalResultDto,
                object : TypeReference<MutableMap<String, Any?>>() {},
            )

        // 3. Perform business logic and enrich the event data with transient info from the AMQP
        // message.
        when (messageType) {
            AmqpConstants.MESSAGE_TYPE_COMPILE_RESULT -> {
                val notification =
                    objectMapper.readValue(messageBody, CompilationResultNotification::class.java)
                if (
                    notification.status == JobStatus.SUCCESS &&
                        notification.compiledProgramRef != null
                ) {
                    playerUpdateService.updatePlayerOnCompileSuccess(
                        userId = notification.userId.toInt(),
                        jobId = jobUUID,
                        compiledProgramRef = notification.compiledProgramRef!!,
                        compileTime = notification.timestamp,
                    )
                }
            }

            AmqpConstants.MESSAGE_TYPE_EXECUTE_RESULT -> {
                val notification =
                    objectMapper.readValue(messageBody, ExecutionResultNotification::class.java)
                playerUpdateService.handleExecutionResult(
                    notification.userId.toInt(),
                    notification.status,
                )

                // Enrich the base data with transient info from the notification.
                baseEventData["action"] = notification.action
                baseEventData["tickNumber"] = notification.tickNumber
                baseEventData["newMemoryData"] = notification.newMemoryData
            }

            else -> {
                logger.warn(
                    "Unknown message type '{}' received for JobId: {}",
                    messageType,
                    correlationId,
                )
            }
        }

        // 4. Publish the final, enriched event to the appropriate SSE stream.
        jobFlowService.publishEvent(
            jobUUID,
            JobSseEvent(
                jobId = correlationId,
                eventType = "FINAL_RESULT",
                status = finalResultDto.status,
                message = "Job finished with status: ${finalResultDto.status}",
                data = baseEventData,
            ),
        )
    }

    /**
     * Gracefully stops the listener container and cancels all running coroutines on application
     * shutdown.
     */
    @PreDestroy
    fun shutdown() {
        logger.info("Shutting down ResultListener...")
        listenerContainer?.stop()
        scope.cancel("Application is shutting down.")
    }
}
