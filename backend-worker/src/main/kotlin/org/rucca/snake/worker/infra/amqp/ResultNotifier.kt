package org.rucca.snake.worker.infra.amqp

import org.rucca.snake.common.constants.AmqpConstants
import org.rucca.snake.common.domain.model.CompilationResultNotification
import org.rucca.snake.common.domain.model.ExecutionResultNotification
import org.rucca.snake.common.domain.model.JobStatus
import org.slf4j.LoggerFactory
import org.springframework.amqp.AmqpException
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

@Service
class ResultNotifier(
    private val rabbitTemplate: RabbitTemplate,
    @Value("\${amqp.exchange.results}") private val resultsExchangeName: String,
    @Value("\${amqp.routingkey.result}") private val resultRoutingKey: String // Common routing key for results? Or specific ones?
    // Or inject specific routing keys if needed:
    // @Value("\${amqp.routingkey.result.compile}") private val compileResultRoutingKey: String,
    // @Value("\${amqp.routingkey.result.execute}") private val executeResultRoutingKey: String
) {
    private val logger = LoggerFactory.getLogger(ResultNotifier::class.java)

    suspend fun notifyCompilationResult(jobId: UUID, userId: Long, status: JobStatus, compiledProgramRef: String? = null) {
        val notification = CompilationResultNotification(
            jobId = jobId.toString(),
            userId = userId,
            status = status,
            compiledProgramRef = if (status == JobStatus.SUCCESS) compiledProgramRef else null, // Only send ref on success
            timestamp = Instant.now()
        )
        sendNotification(notification, AmqpConstants.MESSAGE_TYPE_COMPILE_RESULT, jobId.toString())
    }

    suspend fun notifyExecutionResult(
        jobId: UUID,
        userId: Long,
        status: JobStatus,
        action: String?,
        newMemoryData: String?,
        sessionId: String,
        tickNumber: Int
    ) {
        val notification = ExecutionResultNotification(
            jobId = jobId.toString(),
            userId = userId,
            status = status,
            timestamp = Instant.now(),
            newMemoryData = newMemoryData,
            sessionId = sessionId,
            tickNumber = tickNumber,
            action = action // New field
        )
        sendNotification(notification, AmqpConstants.MESSAGE_TYPE_EXECUTE_RESULT, jobId.toString())
    }

    private suspend fun <T : Any> sendNotification(payload: T, messageType: String, jobId: String) {
        try {
            // Send notification on IO dispatcher
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                rabbitTemplate.convertAndSend(resultsExchangeName, resultRoutingKey, payload) { message ->
                    message.messageProperties.correlationId = jobId // Use original jobId
                    message.messageProperties.headers[AmqpConstants.HEADER_MESSAGE_TYPE] = messageType
                    // Notifications might not need persistence? Or make it configurable.
                    // message.messageProperties.deliveryMode = MessageDeliveryMode.NON_PERSISTENT
                    message
                }
            }
            logger.info("Sent result notification for job {} (Type: {}) to exchange '{}'", jobId, messageType, resultsExchangeName)
        } catch (e: AmqpException) {
            logger.error("Failed to send result notification for job {} (Type: {}): {}", jobId, messageType, e.message)
            // Log error, but typically don't re-throw here as the main task might have succeeded.
            // Consider retry or alternative notification mechanism if critical.
        } catch (e: Exception) {
            logger.error("Unexpected error sending result notification for job {}: {}", jobId, e.message, e)
        }
    }
}
