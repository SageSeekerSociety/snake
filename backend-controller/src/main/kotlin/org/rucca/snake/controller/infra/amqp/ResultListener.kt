package org.rucca.snake.controller.infra.amqp

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
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
    @RabbitListener(
        queues = ["\${amqp.queue.results:oj.results.notify}"]
    ) // Use configured queue name
    fun handleResultMessage(message: Message) {
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

        // Launch processing in a coroutine to avoid blocking listener thread
        scope.launch {
            try {
                var finalStatus: JobStatus? = null
                var errorMessage: String? = null

                when (messageType) {
                    AmqpConstants.MESSAGE_TYPE_COMPILE_RESULT -> {
                        val notification: CompilationResultNotification =
                            objectMapper.readValue(messageBody)
                        finalStatus = notification.status
                        // Publish intermediate status update
                        jobFlowService.publishEvent(
                            jobUUID,
                            JobSseEvent(
                                jobId = correlationId,
                                eventType = "STATUS_UPDATE",
                                status = finalStatus,
                                message =
                                    if (finalStatus == JobStatus.FAILED) "Compilation failed"
                                    else "Compilation finished",
                            ),
                        )

                        if (
                            notification.status == JobStatus.SUCCESS &&
                                notification.compiledProgramRef != null
                        ) {
                            logger.info(
                                "Processing successful compilation result for job {}, user {}",
                                notification.jobId,
                                notification.userId,
                            )
                            // Update Player record
                            playerUpdateService.updatePlayerOnCompileSuccess(
                                userId = notification.userId.toInt(),
                                jobId = jobUUID,
                                compiledProgramRef = notification.compiledProgramRef!!,
                                compileTime = notification.timestamp,
                            )
                        } else {
                            logger.info(
                                "Ignoring non-successful compilation result for job {}",
                                notification.jobId,
                            )
                            errorMessage =
                                "Compilation failed." // Set error message for final event
                        }
                    }

                    AmqpConstants.MESSAGE_TYPE_EXECUTE_RESULT -> {
                        val notification: ExecutionResultNotification =
                            objectMapper.readValue(messageBody)
                        finalStatus = notification.status
                        logger.info(
                            "Processing execution result for job {}, user {}: {}",
                            notification.jobId,
                            notification.userId,
                            notification.status,
                        )
                        // Publish intermediate status update
                        // Construct detailed event data
                        val eventData = mapOf(
                            "action" to notification.action,
                            "sessionId" to notification.sessionId,
                            "tickNumber" to notification.tickNumber,
                            "aiUserId" to notification.userId,
                            "newMemoryData" to notification.newMemoryData,
                            // "errorDetails" to notification.errorDetails, // If errorDetails were added to notification DTO
                        )

                        jobFlowService.publishEvent(
                            jobUUID,
                            JobSseEvent(
                                jobId = correlationId,
                                eventType = if (finalStatus == JobStatus.SUCCESS) "EXECUTION_SUCCESS" else "EXECUTION_FAILURE",
                                status = finalStatus,
                                message = "Execution finished with status: $finalStatus. Action: ${notification.action}",
                                data = eventData,
                            ),
                        )
                        // Optional: Handle execution results (e.g., disable player)
                        playerUpdateService.handleExecutionResult(
                            notification.userId.toInt(),
                            notification.status,
                        )
                        if (finalStatus != JobStatus.SUCCESS) {
                            errorMessage = "Execution finished with status: $finalStatus"
                        }
                    }

                    else -> {
                        logger.warn(
                            "Received result notification with unknown type '{}' for JobId: {}",
                            messageType,
                            correlationId,
                        )
                        errorMessage = "Received unknown result type."
                    }
                }

                // After processing, fetch the full final result from DB and publish it
                if (finalStatus != null) {
                    val finalResultDto = jobQueryService.getJobStatusAndResult(jobUUID)
                    if (finalResultDto != null) {
                        jobFlowService.publishFinalResult(jobUUID, finalResultDto)
                        logger.info("Published final result event for job {}", correlationId)
                    } else {
                        // If final result not found in DB (shouldn't happen often), publish an
                        // error
                        jobFlowService.publishError(
                            jobUUID,
                            errorMessage
                                ?: "Job completed, but failed to fetch final result details.",
                        )
                        logger.error(
                            "Job {} finished with status {}, but couldn't fetch details from DB.",
                            correlationId,
                            finalStatus,
                        )
                    }
                } else if (errorMessage != null) {
                    // Handle cases where status wasn't determined but there was an error
                    jobFlowService.publishError(jobUUID, errorMessage)
                }
            } catch (e: Exception) {
                logger.error(
                    "Failed to process result notification for JobId {}: {}",
                    correlationId,
                    e.message,
                    e,
                )
                // Publish an error event to the SSE stream if possible
                jobFlowService.publishError(
                    jobUUID,
                    "Internal error processing result notification.",
                    e.message,
                )
            }
        } // End coroutine launch
    }
}
