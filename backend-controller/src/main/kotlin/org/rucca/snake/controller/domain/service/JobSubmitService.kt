package org.rucca.snake.controller.domain.service

import io.micrometer.core.instrument.MeterRegistry
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.context.Context
import io.opentelemetry.context.propagation.ContextPropagators
import io.opentelemetry.context.propagation.TextMapSetter
import io.opentelemetry.extension.kotlin.asContextElement
import java.io.InputStream
import java.time.Instant
import java.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import org.rucca.snake.common.constants.AmqpConstants
import org.rucca.snake.common.domain.model.CompilationRequest
import org.rucca.snake.common.domain.model.ExecutionRequest
import org.rucca.snake.common.domain.model.JobStatus
import org.rucca.snake.common.infra.persistence.entity.CompilationJob
import org.rucca.snake.common.infra.persistence.entity.ExecutionJob
import org.rucca.snake.common.infra.persistence.repository.CompilationJobRepository
import org.rucca.snake.common.infra.persistence.repository.ExecutionJobRepository
import org.rucca.snake.controller.domain.model.BatchExecutionItem
import org.rucca.snake.controller.domain.model.JobSseEvent
import org.rucca.snake.controller.infra.storage.MinioService
import org.rucca.snake.controller.utils.withSuspendingSpan
import org.slf4j.LoggerFactory
import org.springframework.amqp.AmqpException
import org.springframework.amqp.core.MessageProperties
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DataAccessException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class JobSubmitService(
    private val compilationJobRepository: CompilationJobRepository,
    private val executionJobRepository: ExecutionJobRepository,
    private val rabbitTemplate: RabbitTemplate,
    private val minioService: MinioService,
    private val jobFlowService: JobFlowService,
    private val meterRegistry: MeterRegistry,
    openTelemetry: OpenTelemetry,
    @Value("\${amqp.exchange.requests}") private val requestsExchangeName: String,
    @Value("\${amqp.routingkey.compile}") private val compileRoutingKey: String,
    @Value("\${amqp.routingkey.execute}") private val executeRoutingKey: String,
) {
    private val tracer = openTelemetry.getTracer(JobSubmitService::class.java.name)
    private val propagators: ContextPropagators = openTelemetry.propagators

    private val logger = LoggerFactory.getLogger(JobSubmitService::class.java)

    private object AmqpSetter : TextMapSetter<MessageProperties> {
        override fun set(carrier: MessageProperties?, key: String, value: String) {
            if (carrier != null) carrier.headers[key] = value
        }
    }

    private fun submissionCounter(type: String) =
        meterRegistry.counter(
            "job.submissions.total",
            "type",
            type, // Tag: 任务类型 (compilation 或 execution)
        )

    private fun logCurrentTrace(where: String) {
        val sc = Span.current().spanContext
        logger.info(
            "otel where={} traceId={} spanId={} sampled={}",
            where,
            sc.traceId,
            sc.spanId,
            sc.isSampled,
        )
    }

    private suspend fun <T : Any> sendWithTracing(
        operation: String,
        exchange: String,
        routingKey: String,
        payload: T,
        jobIdAttr: String? = null,
        customize: (org.springframework.amqp.core.Message) -> org.springframework.amqp.core.Message,
    ) {
        val parentCtx = Context.current()

        withContext(Dispatchers.IO + parentCtx.asContextElement()) {
            val span =
                tracer
                    .spanBuilder("amqp.publish.$operation")
                    .setSpanKind(SpanKind.PRODUCER)
                    .setParent(parentCtx)
                    .setAttribute(AttributeKey.stringKey("messaging.system"), "rabbitmq")
                    .setAttribute(AttributeKey.stringKey("messaging.destination.name"), routingKey)
                    .setAttribute(AttributeKey.stringKey("messaging.operation"), "publish")
                    .apply {
                        if (jobIdAttr != null)
                            setAttribute(AttributeKey.stringKey("app.job_id"), jobIdAttr)
                    }
                    .startSpan()

            try {
                (span.makeCurrent()).use {
                    logCurrentTrace("publish.$operation.beforeInject")

                    rabbitTemplate.convertAndSend(exchange, routingKey, payload) { msg ->
                        val customized = customize(msg)

                        propagators.textMapPropagator.inject(
                            Context.current(),
                            customized.messageProperties,
                            AmqpSetter,
                        )

                        val tp = customized.messageProperties.headers["traceparent"]
                        logger.info("amqp headers after inject: traceparent={}", tp)

                        logCurrentTrace("publish.$operation.afterInject")
                        customized
                    }
                    span.setStatus(StatusCode.OK)
                }
            } catch (e: Exception) {
                span.recordException(e)
                span.setStatus(StatusCode.ERROR, e.message ?: "amqp publish error")
                throw e
            } finally {
                span.end()
            }
        }
    }

    /**
     * Submits a new compilation task. Creates a record in the database and sends a message to the
     * compilation queue. Uses @Transactional to ensure DB save and MQ send are within the same
     * transaction boundary (Note: MQ send won't be truly transactional without extra setup like
     * outbox pattern, but @Transactional helps manage DB commit/rollback).
     *
     * @param userId The ID of the user submitting the code.
     * @param sourceCodeStream The InputStream of the source code file.
     * @param sourceCodeSize The size of the source code file.
     * @return The created CompilationJob entity with its generated jobId.
     * @throws DataAccessException if database interaction fails.
     * @throws AmqpException if sending the message to RabbitMQ fails.
     * @throws RuntimeException for other unexpected errors.
     */
    @Transactional
    suspend fun submitCompilation(
        userId: Long,
        sourceCodeStream: InputStream,
        sourceCodeSize: Long,
    ): CompilationJob =
        tracer.withSuspendingSpan("job.submit.compilation") {
            logCurrentTrace("submitCompilation.entry")

            submissionCounter("compilation").increment()

            val jobId = UUID.randomUUID()
            val submitTime = Instant.now()
            // Define the object key for the source code in MinIO
            val sourceCodeObjectKey = "sources/$userId/$jobId/source.cpp" // Example key structure

            // 1. Upload Source Code to MinIO first
            val uploadSuccess =
                try {
                    tracer.withSuspendingSpan(
                        "minio.upload_source_on_submit",
                        kind = SpanKind.CLIENT,
                        ctx = Dispatchers.IO,
                    ) {
                        minioService.uploadStream(
                            objectKey = sourceCodeObjectKey,
                            inputStream = sourceCodeStream,
                            size = sourceCodeSize, // Provide size
                            contentType = "text/plain", // Or "text/x-c++src" etc.
                        )
                    }
                } catch (e: Exception) {
                    logger.error(
                        "Failed to upload source code to MinIO for potential job $jobId (user $userId)",
                        e,
                    )
                    // If upload fails, we cannot proceed. Throw an exception that leads to API
                    // error
                    // response.
                    throw RuntimeException("Failed to store source code before submitting job", e)
                } finally {
                    // Important: Ensure the stream provided by MultipartFile is closed if necessary
                    // Spring might handle this, but defensive closing is good if unsure.
                    try {
                        withContext(Dispatchers.IO + Context.current().asContextElement()) {
                            sourceCodeStream.close()
                        }
                    } catch (_: Exception) {}
                }

            if (!uploadSuccess) {
                // Should be caught by the try-catch above, but double-check
                throw RuntimeException("MinIO upload returned failure for source code")
            }
            logger.info("Uploaded source code for job {} to MinIO: {}", jobId, sourceCodeObjectKey)

            // 2. Create Job Entity with the reference
            val compilationJob =
                CompilationJob(
                    jobId = jobId,
                    userId = userId,
                    status = JobStatus.PENDING,
                    submitTime = submitTime,
                    sourceCodeRef = sourceCodeObjectKey, // Store the MinIO key
                )

            // 3. Save to Database
            val savedJob: CompilationJob =
                try {
                    tracer.withSuspendingSpan("db.save_compilation_job", ctx = Dispatchers.IO) {
                        compilationJobRepository.save(compilationJob)
                    }
                } catch (e: DataAccessException) {
                    logger.error(
                        "Failed to save compilation job {} to database for user {}: {}",
                        jobId,
                        userId,
                        e.message,
                    )
                    // Consider deleting the uploaded source from MinIO if DB save fails
                    // (compensation
                    // logic)
                    try {
                        minioService.deleteObject(sourceCodeObjectKey)
                    } catch (_: Exception) {
                        /* Log delete failure */
                    }
                    throw e
                } catch (e: Exception) {
                    logger.error(
                        "Unexpected error saving compilation job {} for user {}: {}",
                        jobId,
                        userId,
                        e.message,
                        e,
                    )
                    try {
                        minioService.deleteObject(sourceCodeObjectKey)
                    } catch (_: Exception) {
                        /* Log delete failure */
                    }
                    throw RuntimeException(
                        "Unexpected error during DB save for compilation job $jobId",
                        e,
                    )
                }
            logger.info(
                "Saved compilation job {} with sourceRef '{}' and status PENDING",
                jobId,
                sourceCodeObjectKey,
            )

            jobFlowService.getJobFlow(savedJob.jobId) // Ensure flow exists
            jobFlowService.publishEvent(
                savedJob.jobId,
                JobSseEvent(
                    jobId = savedJob.jobId.toString(),
                    eventType = "SUBMITTED",
                    status = JobStatus.SUBMITTED, // Or PENDING if not updating DB here
                    message = "Compilation job submitted.",
                ),
            )

            // 4. Create AMQP Request DTO (now contains the reference)
            val request =
                CompilationRequest(
                    jobId = jobId.toString(),
                    userId = userId,
                    sourceCodeRef = sourceCodeObjectKey, // Pass the reference
                    timestamp = submitTime,
                )

            // 5. Send to RabbitMQ
            try {
                sendWithTracing(
                    operation = "compile",
                    exchange = requestsExchangeName,
                    routingKey = compileRoutingKey,
                    payload = request,
                    jobIdAttr = jobId.toString(),
                ) { message ->
                    message.messageProperties.correlationId = jobId.toString()
                    message.messageProperties.headers[AmqpConstants.HEADER_MESSAGE_TYPE] =
                        AmqpConstants.MESSAGE_TYPE_COMPILE
                    message
                }
                logger.info(
                    "Sent compilation request for job {} to exchange '{}'",
                    jobId,
                    requestsExchangeName,
                )

                // Optional: Update DB status to SUBMITTED
                // ... (same logic as before, update savedJob status and save again) ...

            } catch (e: AmqpException) {
                logger.error(
                    "Failed to send compilation request for job {} to RabbitMQ: {}",
                    jobId,
                    e.message,
                )
                // If DB save succeeded but MQ failed, flow exists but job won't process.
                // Publish an error event?
                savedJob.let {
                    jobFlowService.publishError(it.jobId, "Failed to send job to processing queue.")
                }
                // Transaction will rollback DB save. The uploaded MinIO object remains (orphan).
                // Requires more complex cleanup/compensation logic if strict consistency is needed.
                throw e
            } catch (e: Exception) {
                logger.error(
                    "Unexpected error sending compilation request for job {} to RabbitMQ: {}",
                    jobId,
                    e.message,
                    e,
                )
                savedJob.let {
                    jobFlowService.publishError(it.jobId, "Unexpected error during job submission.")
                }
                throw RuntimeException(
                    "Unexpected error during AMQP send for compilation job $jobId",
                    e,
                )
            }

            savedJob
        }

    /**
     * Submits a batch of execution tasks. Creates records in the database and sends messages to the
     * execution queue for each item. This method iterates through the batch, calling a
     * transactional helper method for each item. If any submission fails, it attempts to continue
     * with others but reports individual failures.
     *
     * @param requests List of execution request items from the API.
     * @return A map where Key is the clientRequestId (or generated jobId if null) and Value is a
     *   Result indicating success (Ok containing jobId) or failure (Err containing error message).
     */
    suspend fun submitBatchExecution(
        requests: List<BatchExecutionItem>,
        finalSessionId: String,
        requestingUserId: Long,
    ): Map<String, Result<String>> =
        tracer.withSuspendingSpan("job.submit.batch_execution") {
            val results = mutableMapOf<String, Result<String>>()
            val submittedJobIds = mutableListOf<UUID>()

            requests.forEachIndexed { index, item ->
                // Use clientRequestId if available, otherwise generate a temporary key for the
                // result
                // map
                val resultKey = item.clientRequestId ?: "batch_item_${index}_${UUID.randomUUID()}"
                var submittedJobId: UUID? = null
                try {
                    // Call a separate transactional method for each item
                    val executionJob =
                        submitSingleExecutionInternal(item, finalSessionId, requestingUserId)
                    submittedJobId = executionJob.jobId
                    jobFlowService.getJobFlow(submittedJobId)
                    jobFlowService.publishEvent(
                        submittedJobId,
                        JobSseEvent(
                            jobId = submittedJobId.toString(),
                            eventType = "SUBMITTED",
                            status = JobStatus.SUBMITTED, // Assuming internal method updates status
                            message = "Execution job submitted.",
                            data =
                                mapOf(
                                    "userId" to item.userId,
                                    "clientRequestId" to item.clientRequestId,
                                    "tickNumber" to item.tickNumber,
                                    "sessionId" to finalSessionId,
                                    "requestingUserId" to requestingUserId,
                                ),
                            sessionId = finalSessionId,
                        ),
                    )
                    results[resultKey] =
                        Result.success(submittedJobId.toString()) // Return success with jobId
                    submittedJobIds.add(submittedJobId)
                } catch (e: Exception) {
                    // Catch exceptions from the internal submission method (DB or AMQP failures)
                    logger.error(
                        "Failed to submit batch execution item (User: {}, ClientReqId: {}): {}",
                        item.userId,
                        item.clientRequestId ?: "N/A",
                        e.message,
                    )
                    submittedJobId?.let { failedJobId ->
                        jobFlowService.publishError(
                            failedJobId,
                            "Failed during submission process: ${e.message}",
                            mapOf(
                                "userId" to item.userId,
                                "clientRequestId" to item.clientRequestId,
                                "tickNumber" to item.tickNumber,
                                "sessionId" to finalSessionId,
                                "requestingUserId" to requestingUserId,
                            ),
                            sessionId = finalSessionId,
                        )
                    }
                    results[resultKey] = Result.failure(e) // Return failure with exception
                }
            }
            results
        }

    /**
     * Submits a single execution job, persisting it and sending the execution request to RabbitMQ
     * within its own transaction.
     *
     * @param item The execution request details.
     * @param finalSessionId The session ID associated with the execution.
     * @param requestingUserId The ID of the user initiating the request.
     * @return The persisted ExecutionJob entity.
     * @throws IllegalArgumentException If the session ID format is invalid.
     * @throws DataAccessException If saving the job to the database fails.
     * @throws AmqpException If sending the execution request to RabbitMQ fails.
     * @throws RuntimeException For unexpected errors during database or messaging operations.
     */
    @Transactional // Each execution submission gets its own transaction
    internal suspend fun submitSingleExecutionInternal(
        item: BatchExecutionItem,
        finalSessionId: String,
        requestingUserId: Long,
    ): ExecutionJob {
        logCurrentTrace("submitSingleExecution.entry")

        submissionCounter("execution").increment()

        val jobId = UUID.randomUUID()
        val submitTime = Instant.now()

        // 1. Create Job Entity
        val sessionUuid =
            runCatching { UUID.fromString(finalSessionId) }
                .getOrElse {
                    throw IllegalArgumentException("Invalid sessionId format: $finalSessionId", it)
                }
        val executionJob =
            ExecutionJob(
                jobId = jobId,
                userId = item.userId,
                status = JobStatus.PENDING,
                submitTime = submitTime,
                clientRequestId = item.clientRequestId,
                sessionId = sessionUuid,
                tickNumber = item.tickNumber,
                requestingUserId = requestingUserId,
            )

        // 2. Save to Database
        val savedJob: ExecutionJob =
            try {
                tracer.withSuspendingSpan("db.save_execution_job", ctx = Dispatchers.IO) {
                    executionJobRepository.save(executionJob)
                }
            } catch (e: DataAccessException) {
                logger.error(
                    "Failed to save execution job {} to database for user {}: {}",
                    jobId,
                    item.userId,
                    e.message,
                )
                throw e
            } catch (e: Exception) {
                logger.error(
                    "Unexpected error saving execution job {} for user {}: {}",
                    jobId,
                    item.userId,
                    e.message,
                    e,
                )
                throw RuntimeException(
                    "Unexpected error during DB save for execution job $jobId",
                    e,
                )
            }
        logger.info("Saved execution job {} with status PENDING", jobId)

        // 3. Create AMQP Request DTO
        val request =
            ExecutionRequest(
                jobId = jobId.toString(),
                aiOwnerUserId = item.userId,
                inputData = item.inputData, // Consider reference if large
                cpuTimeLimitSeconds = item.cpuTimeLimitSeconds,
                memoryLimitKb = item.memoryLimitKb,
                wallTimeLimitSeconds = item.wallTimeLimitSeconds,
                clientRequestId = item.clientRequestId, // Pass through
                timestamp = submitTime,
                // New fields
                sessionId = finalSessionId,
                tickNumber = item.tickNumber,
                requestingUserId = requestingUserId, // User who made the HTTP call
            )

        // 4. Send to RabbitMQ
        try {
            sendWithTracing(
                operation = "execute",
                exchange = requestsExchangeName,
                routingKey = executeRoutingKey,
                payload = request,
                jobIdAttr = jobId.toString(),
            ) { message ->
                message.messageProperties.correlationId = jobId.toString()
                message.messageProperties.headers[AmqpConstants.HEADER_MESSAGE_TYPE] =
                    AmqpConstants.MESSAGE_TYPE_EXECUTE
                item.clientRequestId?.let {
                    message.messageProperties.headers["clientRequestId"] = it
                }
                message
            }

            logger.info(
                "Sent execution request for job {} to exchange '{}' with routing key '{}'",
                jobId,
                requestsExchangeName,
                executeRoutingKey,
            )

            // 5. Optional: Update status to SUBMITTED
            /*
            try {
                 withContext(Dispatchers.IO) {
                    savedJob.status = JobStatus.SUBMITTED
                    executionJobRepository.save(savedJob)
                 }
                 logger.info("Updated execution job {} status to SUBMITTED", jobId)
            } catch (e: DataAccessException) {
                 logger.error("Failed to update execution job {} status to SUBMITTED: {}", jobId, e.message)
            }
            */

        } catch (e: AmqpException) {
            logger.error(
                "Failed to send execution request for job {} to RabbitMQ: {}",
                jobId,
                e.message,
            )
            throw e // Rollback DB
        } catch (e: Exception) {
            logger.error(
                "Unexpected error sending execution request for job {} to RabbitMQ: {}",
                jobId,
                e.message,
                e,
            )
            throw RuntimeException("Unexpected error during AMQP send for execution job $jobId", e)
        }

        return savedJob
    }
}
