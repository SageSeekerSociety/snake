package org.rucca.snake.worker.infra.amqp

import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.context.Context
import io.opentelemetry.context.propagation.TextMapSetter
import java.time.Instant
import java.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import org.rucca.snake.common.constants.AmqpConstants
import org.rucca.snake.common.domain.model.CompilationResultNotification
import org.rucca.snake.common.domain.model.ExecutionResultNotification
import org.rucca.snake.common.domain.model.JobStatus
import org.slf4j.LoggerFactory
import org.springframework.amqp.AmqpException
import org.springframework.amqp.core.MessageProperties
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

@Service
class ResultNotifier(
    private val rabbitTemplate: RabbitTemplate,
    openTelemetry: OpenTelemetry,
    @Value("\${amqp.exchange.results}") private val resultsExchangeName: String,
    @Value("\${amqp.routingkey.result}") private val resultRoutingKey: String,
) {
    private val logger = LoggerFactory.getLogger(ResultNotifier::class.java)
    private val propagators = openTelemetry.propagators

    // 创建一个 TextMapSetter 实例，用于操作 MessageProperties
    private val rabbitMqSetter =
        TextMapSetter<MessageProperties> { carrier, key, value ->
            carrier?.headers?.put(key, value)
        }

    suspend fun notifyCompilationResult(
        jobId: UUID,
        userId: Long,
        status: JobStatus,
        compiledProgramRef: String? = null,
    ) {
        val notification =
            CompilationResultNotification(
                jobId = jobId.toString(),
                userId = userId,
                status = status,
                compiledProgramRef =
                    if (status == JobStatus.SUCCESS) compiledProgramRef
                    else null, // Only send ref on success
                timestamp = Instant.now(),
            )
        sendNotification(notification, AmqpConstants.MESSAGE_TYPE_COMPILE_RESULT, jobId.toString())
    }

    suspend fun notifyExecutionResult(notification: ExecutionResultNotification) {
        sendNotification(
            notification,
            AmqpConstants.MESSAGE_TYPE_EXECUTE_RESULT,
            notification.jobId,
        )
    }

    private suspend fun <T : Any> sendNotification(payload: T, messageType: String, jobId: String) {
        try {
            // Send notification on IO dispatcher
            withContext(Dispatchers.IO) {
                rabbitTemplate.convertAndSend(resultsExchangeName, resultRoutingKey, payload) {
                    message ->
                    message.messageProperties.correlationId = jobId // Use original jobId
                    message.messageProperties.headers[AmqpConstants.HEADER_MESSAGE_TYPE] =
                        messageType

                    propagators.textMapPropagator.inject(
                        Context.current(),
                        message.messageProperties,
                        rabbitMqSetter,
                    )

                    message
                }
            }
            logger.info(
                "Sent result notification for job {} (Type: {}) to exchange '{}'",
                jobId,
                messageType,
                resultsExchangeName,
            )
        } catch (e: AmqpException) {
            logger.error(
                "Failed to send result notification for job {} (Type: {}): {}",
                jobId,
                messageType,
                e.message,
            )
            // Log error, but typically don't re-throw here as the main task might have succeeded.
            // Consider retry or alternative notification mechanism if critical.
        } catch (e: Exception) {
            logger.error(
                "Unexpected error sending result notification for job {}: {}",
                jobId,
                e.message,
                e,
            )
        }
    }
}
