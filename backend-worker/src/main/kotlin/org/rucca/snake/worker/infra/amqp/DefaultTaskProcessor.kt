package org.rucca.snake.worker.infra.amqp

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.context.propagation.ContextPropagators
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import org.rucca.snake.common.constants.AmqpConstants
import org.rucca.snake.common.domain.model.CompilationRequest
import org.rucca.snake.common.domain.model.ExecutionRequest
import org.rucca.snake.worker.domain.service.CompileService
import org.rucca.snake.worker.domain.service.ExecuteService
import org.slf4j.LoggerFactory
import org.springframework.amqp.core.Message
import org.springframework.stereotype.Component

@Component
class DefaultTaskProcessor(
    private val compileService: CompileService,
    private val executeService: ExecuteService,
    private val objectMapper: ObjectMapper,
    openTelemetry: OpenTelemetry,
) : TaskProcessor {
    private val propagators: ContextPropagators = openTelemetry.propagators

    private val logger = LoggerFactory.getLogger(DefaultTaskProcessor::class.java)

    override suspend fun processMessage(message: Message) {
        val messageProperties = message.messageProperties
        val messageBody = String(message.body, Charsets.UTF_8) // Assume UTF-8 encoding
        val correlationId = messageProperties.correlationId // Use correlationId as jobId
        val messageType = messageProperties.headers[AmqpConstants.HEADER_MESSAGE_TYPE] as? String

        if (correlationId == null) {
            logger.error(
                "Received message without correlationId (jobId). Body: {}",
                messageBody.take(200),
            )
            // Cannot process without jobId, throw exception to trigger NACK (and likely DLQ)
            throw IllegalArgumentException("Message received without correlationId (jobId)")
        }

        if (messageType == null) {
            logger.error(
                "Received message (jobId: {}) without '{}' header. Body: {}",
                correlationId,
                AmqpConstants.HEADER_MESSAGE_TYPE,
                messageBody.take(200),
            )
            // Cannot determine type, throw exception
            throw IllegalArgumentException(
                "Message received without '${AmqpConstants.HEADER_MESSAGE_TYPE}' header"
            )
        }

        logger.info("Processing message - JobId: {}, Type: {}", correlationId, messageType)

        try {
            // Delegate based on message type header
            when (messageType) {
                AmqpConstants.MESSAGE_TYPE_COMPILE -> {
                    val request: CompilationRequest = parseMessageBody(messageBody, correlationId)
                    // Run the service logic on an appropriate dispatcher (IO is suitable for
                    // services doing IO)
                    // The services themselves use withContext for specific blocking calls
                    withContext(Dispatchers.IO) {
                        compileService.processCompilationRequest(request, correlationId)
                    }
                    logger.info(
                        "Successfully processed compilation request for JobId: {}",
                        correlationId,
                    )
                }

                AmqpConstants.MESSAGE_TYPE_EXECUTE -> {
                    val request: ExecutionRequest = parseMessageBody(messageBody, correlationId)
                    withContext(Dispatchers.IO) {
                        executeService.processExecutionRequest(request, correlationId)
                    }
                    logger.info(
                        "Successfully processed execution request for JobId: {}",
                        correlationId,
                    )
                }

                else -> {
                    logger.error(
                        "Unknown message type '{}' for JobId: {}",
                        messageType,
                        correlationId,
                    )
                    throw IllegalArgumentException("Unsupported message type: $messageType")
                }
            }
            // If we reach here without exceptions, processing was successful (from this
            // component's
            // perspective)
            // The ACK will be handled by the TaskPoller based on this method returning
            // normally.

        } catch (e: Exception) {
            // Catch exceptions from parsing or service layer
            logger.error(
                "Exception caught while processing message for JobId: {}. Type: {}. Error: {}",
                correlationId,
                messageType,
                e.message,
                e,
            )
            // Re-throw the exception so the TaskPoller knows processing failed and should NACK
            // the
            // message.
            // The specific services (CompileService, ExecuteService) should have already
            // updated
            // the DB status to FAILED/ERROR.
            throw e // IMPORTANT: Propagate exception for NACK handling
        }
    }

    /** Helper function to parse the JSON message body with proper error handling. */
    private inline fun <reified T> parseMessageBody(messageBody: String, jobId: String): T {
        try {
            return objectMapper.readValue<T>(messageBody)
        } catch (e: Exception) {
            logger.error(
                "Failed to parse message body for JobId: {}. Body: {}. Error: {}",
                jobId,
                messageBody.take(500),
                e.message,
            )
            // Throw a specific exception or a wrapper exception
            throw IllegalArgumentException("Failed to parse message body for JobId $jobId", e)
        }
    }
}
