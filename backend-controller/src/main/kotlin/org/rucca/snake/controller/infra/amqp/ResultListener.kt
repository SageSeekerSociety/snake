package org.rucca.snake.controller.infra.amqp

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.context.Context
import io.opentelemetry.context.propagation.TextMapGetter
import io.opentelemetry.extension.kotlin.asContextElement
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import java.util.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import org.rucca.snake.common.constants.AmqpConstants
import org.rucca.snake.common.domain.model.CompilationResultNotification
import org.rucca.snake.common.domain.model.ExecutionResultNotification
import org.rucca.snake.common.domain.model.JobStatus
import org.rucca.snake.common.utils.withSuspendingSpan
import org.rucca.snake.controller.domain.model.JobSseEvent
import org.rucca.snake.controller.domain.service.JobFlowService
import org.rucca.snake.controller.domain.service.JobQueryService
import org.rucca.snake.controller.domain.service.PlayerUpdateService
import org.slf4j.LoggerFactory
import org.springframework.amqp.core.AcknowledgeMode
import org.springframework.amqp.core.Message
import org.springframework.amqp.core.MessageProperties
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
    private val cacheEvictionPublisher: CacheEvictionPublisher,
    private val connectionFactory: ConnectionFactory,
    openTelemetry: OpenTelemetry,
    @Value("\${amqp.queue.results:oj.results.notify}") private val queueName: String,
    @Value("\${amqp.listener.prefetch:10}") private val prefetchCount: Int,
) {
    private val logger = LoggerFactory.getLogger(ResultListener::class.java)
    private val propagators = openTelemetry.propagators
    private val tracer = openTelemetry.getTracer(ResultListener::class.java.name)

    private object AmqpGetter : TextMapGetter<MessageProperties> {
        override fun keys(carrier: MessageProperties): Iterable<String> = carrier.headers.keys

        override fun get(carrier: MessageProperties?, key: String): String? {
            val v = carrier?.headers?.get(key) ?: return null
            return when (v) {
                is ByteArray -> String(v)
                else -> v.toString()
            }
        }
    }

    private val scope =
        CoroutineScope(Dispatchers.Default + SupervisorJob() + CoroutineName("ResultListenerScope"))
    private var listenerContainer: SimpleMessageListenerContainer? = null

    /** Initializes and starts the programmatic RabbitMQ listener upon bean creation. */
    @OptIn(ExperimentalCoroutinesApi::class)
    @PostConstruct
    fun startListener() {
        val container = SimpleMessageListenerContainer(connectionFactory)
        container.setQueueNames(queueName)
        container.acknowledgeMode = AcknowledgeMode.MANUAL
        container.setPrefetchCount(prefetchCount) // Adjust based on processing capacity
        container.setDefaultRequeueRejected(false)

        // Creates a Kotlin Flow from the callback-based message listener.
        val messageFlow = callbackFlow {
            val listener = ChannelAwareMessageListener { message, channel ->
                val extractedContext =
                    propagators.textMapPropagator.extract(
                        Context.current(),
                        message.messageProperties,
                        AmqpGetter,
                    )
                val rs = trySend(Triple(message, channel!!, extractedContext))
                if (!rs.isSuccess) {
                    val dt = message.messageProperties.deliveryTag
                    channel.basicNack(dt, false, /* requeue */ true)
                    logger.warn(
                        "Backpressure: NACKed deliveryTag={} for queue={}",
                        dt,
                        message.messageProperties.consumerQueue,
                    )
                }
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
            .buffer(prefetchCount)
            .flatMapMerge(concurrency = prefetchCount) { (message, channel, extractedContext) ->
                flow {
                    withContext(extractedContext.asContextElement()) {
                        val deliveryTag = message.messageProperties.deliveryTag
                        val correlationId = message.messageProperties.correlationId
                        try {
                            processMessageInternal(message)
                            channel.basicAck(deliveryTag, false)
                            logger.debug("ACKed job {}", correlationId)
                        } catch (e: Exception) {
                            logger.error("Failed job $correlationId. NACKing.", e)
                            channel.basicNack(deliveryTag, false, false)
                        }
                    }
                    emit(Unit)
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
    private suspend fun processMessageInternal(message: Message) =
        tracer.withSuspendingSpan("job.result.process") {
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

            var sseEvent: JobSseEvent? = null

            when (messageType) {
                AmqpConstants.MESSAGE_TYPE_COMPILE_RESULT -> {
                    val notification =
                        objectMapper.readValue(
                            messageBody,
                            CompilationResultNotification::class.java,
                        )
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

                        // Broadcast cache eviction by object key
                        cacheEvictionPublisher.publishEvict(notification.compiledProgramRef!!)
                    }

                    // Fetch full data for compilation as it's less frequent and has fewer fields
                    val finalResultDto =
                        jobQueryService.getJobStatusAndResult(jobUUID)
                            ?: throw RuntimeException(
                                "Job record not found in DB for job ID: $jobUUID"
                            )

                    val eventData: Map<String, Any?> =
                        objectMapper.convertValue(
                            finalResultDto,
                            object : TypeReference<Map<String, Any?>>() {},
                        )

                    sseEvent =
                        JobSseEvent(
                            jobId = correlationId,
                            eventType = "FINAL_RESULT",
                            status = notification.status,
                            message = "Job finished with status: ${notification.status}",
                            data = eventData,
                        )
                }

                AmqpConstants.MESSAGE_TYPE_EXECUTE_RESULT -> {
                    val notification =
                        objectMapper.readValue(messageBody, ExecutionResultNotification::class.java)
                    playerUpdateService.handleExecutionResult(
                        notification.userId.toInt(),
                        notification.status,
                    )

                    // Build the event data directly from the "fat" notification
                    // to match the structure of ExecutionJobResultDto
                    val eventData =
                        mapOf(
                            "jobId" to notification.jobId,
                            "userId" to notification.userId,
                            "status" to notification.status,
                            "submitTime" to notification.submitTime,
                            "startTime" to notification.startTime,
                            "endTime" to notification.endTime,
                            "errorDetails" to notification.errorDetails,
                            "workerNodeId" to notification.workerNodeId,
                            "programOutput" to notification.action,
                            "programStderr" to notification.programStderr,
                            "cpuTimeSeconds" to notification.cpuTimeSeconds,
                            "memoryKb" to notification.memoryKb,
                            "exitCode" to notification.exitCode,
                            "clientRequestId" to notification.clientRequestId,
                            "action" to notification.action,
                            "tickNumber" to notification.tickNumber,
                            "newMemoryData" to notification.newMemoryData,
                        )

                    sseEvent =
                        JobSseEvent(
                            jobId = correlationId,
                            eventType = "FINAL_RESULT",
                            status = notification.status,
                            message = "Job finished with status: ${notification.status}",
                            data = eventData,
                            sessionId = notification.sessionId,
                        )
                }

                else -> {
                    logger.warn(
                        "Unknown message type '{}' received for JobId: {}",
                        messageType,
                        correlationId,
                    )
                }
            }

            // Publish the final, enriched event to the appropriate SSE stream.
            if (sseEvent != null) {
                jobFlowService.publishEvent(jobUUID, sseEvent)
            }
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
