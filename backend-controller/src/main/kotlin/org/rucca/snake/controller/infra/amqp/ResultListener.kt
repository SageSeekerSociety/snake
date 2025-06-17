package org.rucca.snake.controller.infra.amqp

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.rabbitmq.client.Channel
import java.util.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.rucca.snake.common.constants.AmqpConstants
import org.rucca.snake.common.domain.model.CompilationResultNotification
import org.rucca.snake.common.domain.model.ExecutionResultNotification
import org.rucca.snake.common.domain.model.JobStatus
import org.rucca.snake.controller.domain.model.JobSseEvent
import org.rucca.snake.controller.domain.service.JobFlowService
import org.rucca.snake.controller.domain.service.JobQueryService
import org.rucca.snake.controller.domain.service.PlayerUpdateService
import org.slf4j.LoggerFactory
import org.springframework.amqp.core.Message
import org.springframework.amqp.rabbit.annotation.RabbitListener
import org.springframework.amqp.support.AmqpHeaders
import org.springframework.messaging.handler.annotation.Header
import org.springframework.stereotype.Component

@Component
class ResultListener(
    private val objectMapper: ObjectMapper,
    private val playerUpdateService: PlayerUpdateService,
    private val jobFlowService: JobFlowService,
    private val jobQueryService: JobQueryService,
) {
    private val logger = LoggerFactory.getLogger(ResultListener::class.java)

    // Scope for handling listener logic asynchronously if needed
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Listen to the results queue defined in configuration
    @RabbitListener(queues = ["\${amqp.queue.results:oj.results.notify}"])
    fun handleResultMessage(
        message: Message,
        channel: Channel,
        @Header(AmqpHeaders.DELIVERY_TAG) deliveryTag: Long,
    ) {
        val messageProperties = message.messageProperties
        val messageBody = String(message.body, Charsets.UTF_8)
        val correlationId = messageProperties.correlationId // Original JobId
        val messageType = messageProperties.headers[AmqpConstants.HEADER_MESSAGE_TYPE] as? String

        if (correlationId == null || messageType == null) {
            logger.error(
                "Received invalid result notification: missing correlationId or messageType"
            )
            return
        }
        val jobUUID =
            try {
                UUID.fromString(correlationId)
            } catch (e: Exception) {
                logger.error(
                    "Received result notification with invalid jobId format: {}",
                    correlationId,
                )
                return
            }

        logger.info(
            "Received result notification - JobId: {}, Type: {}",
            correlationId,
            messageType,
        )

        scope.launch {
            try {
                val finalResultDto = jobQueryService.getJobStatusAndResult(jobUUID)

                if (finalResultDto == null) {
                    logger.error(
                        "CRITICAL: Job {} processing complete, but couldn't find record in DB.",
                        correlationId,
                    )
                    jobFlowService.publishError(
                        jobUUID,
                        "Failed to find job record after completion.",
                    )
                    return@launch
                }

                val baseEventData: MutableMap<String, Any?> =
                    objectMapper.convertValue(
                        finalResultDto,
                        object : TypeReference<MutableMap<String, Any?>>() {},
                    )

                when (messageType) {
                    AmqpConstants.MESSAGE_TYPE_COMPILE_RESULT -> {
                        val notification: CompilationResultNotification =
                            objectMapper.readValue(messageBody)

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
                        val notification: ExecutionResultNotification =
                            objectMapper.readValue(messageBody)

                        playerUpdateService.handleExecutionResult(
                            notification.userId.toInt(),
                            notification.status,
                        )

                        baseEventData["action"] = notification.action
                        baseEventData["tickNumber"] = notification.tickNumber
                        baseEventData["newMemoryData"] = notification.newMemoryData
                    }

                    else -> {
                        logger.warn(
                            "Unknown message type '{}' for JobId: {}",
                            messageType,
                            correlationId,
                        )
                    }
                }

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

                channel.basicAck(deliveryTag, false)
                logger.info("Successfully processed and ACKed message for job {}", correlationId)
            } catch (e: Exception) {
                logger.error(
                    "Failed to process result notification for JobId {}: {}",
                    correlationId,
                    e.message,
                    e,
                )
                channel.basicNack(deliveryTag, false, false)
                jobFlowService.publishError(
                    jobUUID,
                    "Internal error processing result notification.",
                    e.message,
                )
            }
        } // End coroutine launch
    }
}
