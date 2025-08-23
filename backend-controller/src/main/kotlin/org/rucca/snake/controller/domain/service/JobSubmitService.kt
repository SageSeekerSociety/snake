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
import org.rucca.snake.common.utils.UuidV7
import org.rucca.snake.common.utils.withSuspendingSpan
import org.rucca.snake.controller.domain.model.BatchExecutionItem
import org.rucca.snake.controller.domain.model.JobSseEvent
import org.rucca.snake.controller.infra.storage.MinioService
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
     * compilation queue.
     *
     * @param userId The ID of the user submitting the code.
     * @param sourceCodeStream The InputStream of the source code file.
     * @param sourceCodeSize The size of the source code file.
     * @return The created CompilationJob entity with its generated jobId.
     * @throws DataAccessException if database interaction fails.
     * @throws AmqpException if sending the message to RabbitMQ fails.
     * @throws RuntimeException for other unexpected errors.
     */
    suspend fun submitCompilation(
        userId: Long,
        sourceCodeStream: InputStream,
        sourceCodeSize: Long,
    ): CompilationJob =
        tracer.withSuspendingSpan("job.submit.compilation") {
            logCurrentTrace("submitCompilation.entry")
            submissionCounter("compilation").increment()

            val jobId = org.rucca.snake.common.utils.UuidV7.generate()
            val submitTime = Instant.now()
            val sourceCodeObjectKey = "sources/$userId/$jobId/source.cpp"

            uploadSourceToMinio(
                sourceCodeObjectKey,
                sourceCodeStream,
                sourceCodeSize,
                jobId,
                userId,
            )
            logger.info("Uploaded source code for job {} to MinIO: {}", jobId, sourceCodeObjectKey)

            val compilationJob =
                CompilationJob(
                    jobId = jobId,
                    userId = userId,
                    status = JobStatus.PENDING,
                    submitTime = submitTime,
                    sourceCodeRef = sourceCodeObjectKey,
                )

            val savedJob = saveCompilationJobInTransaction(compilationJob)
            logger.info(
                "Saved compilation job {} with sourceRef '{}' and status PENDING",
                jobId,
                sourceCodeObjectKey,
            )

            jobFlowService.getJobFlow(savedJob.jobId)
            jobFlowService.publishEvent(
                savedJob.jobId,
                JobSseEvent(
                    jobId = savedJob.jobId.toString(),
                    eventType = "SUBMITTED",
                    status = JobStatus.SUBMITTED,
                    message = "Compilation job submitted.",
                ),
            )

            try {
                sendCompilationRequestToMq(savedJob)
                logger.info(
                    "Sent compilation request for job {} to exchange '{}'",
                    jobId,
                    requestsExchangeName,
                )
            } catch (e: Exception) {
                // Handle the case where DB succeeded but MQ failed.
                // The transaction has already committed, so it won't be rolled back.
                logger.error(
                    "CRITICAL: DB commit succeeded, but failed to send compilation request for job {} to RabbitMQ. " +
                        "This job may require manual intervention. Error: {}",
                    jobId,
                    e.message,
                    e,
                )
                savedJob.let {
                    jobFlowService.publishError(it.jobId, "Failed to send job to processing queue.")
                }
                // Re-throw the exception to let the caller know the submission was not fully
                // successful.
                throw e
            }

            savedJob
        }

    private suspend fun uploadSourceToMinio(
        objectKey: String,
        stream: InputStream,
        size: Long,
        jobId: UUID,
        userId: Long,
    ) {
        try {
            tracer.withSuspendingSpan(
                "minio.upload_source_on_submit",
                kind = SpanKind.CLIENT,
                ctx = Dispatchers.IO,
            ) {
                minioService.uploadStream(
                    objectKey = objectKey,
                    inputStream = stream,
                    size = size,
                    contentType = "text/plain",
                )
            }
        } catch (e: Exception) {
            logger.error(
                "Failed to upload source code to MinIO for potential job $jobId (user $userId)",
                e,
            )
            throw RuntimeException("Failed to store source code before submitting job", e)
        } finally {
            try {
                withContext(Dispatchers.IO + Context.current().asContextElement()) {
                    stream.close()
                }
            } catch (_: Exception) {
                // Ignore close errors
            }
        }
    }

    private suspend fun sendCompilationRequestToMq(job: CompilationJob) {
        val request =
            CompilationRequest(
                jobId = job.jobId.toString(),
                userId = job.userId,
                sourceCodeRef = job.sourceCodeRef,
                timestamp = job.submitTime,
            )
        sendWithTracing(
            operation = "compile",
            exchange = requestsExchangeName,
            routingKey = compileRoutingKey,
            payload = request,
            jobIdAttr = job.jobId.toString(),
        ) { message ->
            message.messageProperties.correlationId = job.jobId.toString()
            message.messageProperties.headers[AmqpConstants.HEADER_MESSAGE_TYPE] =
                AmqpConstants.MESSAGE_TYPE_COMPILE
            message
        }
    }

    @Transactional
    internal suspend fun saveCompilationJobInTransaction(job: CompilationJob): CompilationJob {
        return try {
            tracer.withSuspendingSpan("db.save_compilation_job", ctx = Dispatchers.IO) {
                compilationJobRepository.save(job)
            }
        } catch (e: DataAccessException) {
            logger.error(
                "Failed to save compilation job {} to database for user {}: {}",
                job.jobId,
                job.userId,
                e.message,
                e,
            )
            // If DB save fails, we might have an orphan file in MinIO.
            // A cleanup job or manual cleanup might be needed for such cases.
            try {
                minioService.deleteObject(job.sourceCodeRef)
            } catch (delEx: Exception) {
                logger.warn(
                    "Failed to clean up orphan MinIO object ${job.sourceCodeRef} after DB save failure.",
                    delEx,
                )
            }
            throw e
        }
    }

    /**
     * Submits a batch of execution tasks. This method itself is not transactional; it calls the
     * transactional helper for each item.
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
                val resultKey = item.clientRequestId ?: "batch_item_${index}_${UuidV7.generate()}"
                var submittedJobId: UUID? = null
                try {
                    // Call a separate method for each item, which now correctly manages its own
                    // transaction.
                    val executionJob =
                        submitSingleExecutionInternal(item, finalSessionId, requestingUserId)
                    submittedJobId = executionJob.jobId
                    jobFlowService.getJobFlow(submittedJobId)
                    jobFlowService.publishEvent(
                        submittedJobId,
                        JobSseEvent(
                            jobId = submittedJobId.toString(),
                            eventType = "SUBMITTED",
                            status = JobStatus.SUBMITTED,
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
                    results[resultKey] = Result.success(submittedJobId.toString())
                    submittedJobIds.add(submittedJobId)
                } catch (e: Exception) {
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
                    results[resultKey] = Result.failure(e)
                }
            }
            results
        }

    /**
     * Submits a single execution job.
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
    internal suspend fun submitSingleExecutionInternal(
        item: BatchExecutionItem,
        finalSessionId: String,
        requestingUserId: Long,
    ): ExecutionJob {
        logCurrentTrace("submitSingleExecution.entry")
        submissionCounter("execution").increment()

        val jobId = org.rucca.snake.common.utils.UuidV7.generate()
        val submitTime = Instant.now()
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

        val savedJob = saveExecutionJobInTransaction(executionJob)
        logger.info("Saved execution job {} with status PENDING", jobId)

        try {
            sendExecutionRequestToMq(savedJob, item, finalSessionId, requestingUserId)
            logger.info(
                "Sent execution request for job {} to exchange '{}' with routing key '{}'",
                jobId,
                requestsExchangeName,
                executeRoutingKey,
            )
        } catch (e: Exception) {
            // Handle the case where DB succeeded but MQ failed.
            logger.error(
                "CRITICAL: DB commit succeeded, but failed to send execution request for job {} to RabbitMQ. " +
                    "This job may require manual intervention. Error: {}",
                jobId,
                e.message,
                e,
            )
            // Re-throw the exception to let the caller (submitBatchExecution) know.
            throw e
        }

        return savedJob
    }

    private suspend fun sendExecutionRequestToMq(
        savedJob: ExecutionJob,
        item: BatchExecutionItem,
        finalSessionId: String,
        requestingUserId: Long,
    ) {
        val publishTs = java.time.Instant.now()
        val request =
            ExecutionRequest(
                jobId = savedJob.jobId.toString(),
                aiOwnerUserId = item.userId,
                inputData = item.inputData,
                cpuTimeLimitSeconds = item.cpuTimeLimitSeconds,
                memoryLimitKb = item.memoryLimitKb,
                wallTimeLimitSeconds = item.wallTimeLimitSeconds,
                clientRequestId = item.clientRequestId,
                timestamp = publishTs,
                sessionId = finalSessionId,
                tickNumber = item.tickNumber,
                requestingUserId = requestingUserId,
            )
        sendWithTracing(
            operation = "execute",
            exchange = requestsExchangeName,
            routingKey = executeRoutingKey,
            payload = request,
            jobIdAttr = savedJob.jobId.toString(),
        ) { message ->
            message.messageProperties.correlationId = savedJob.jobId.toString()
            message.messageProperties.headers[AmqpConstants.HEADER_MESSAGE_TYPE] =
                AmqpConstants.MESSAGE_TYPE_EXECUTE
            item.clientRequestId?.let { message.messageProperties.headers["clientRequestId"] = it }
            // Add publish timestamp header (ISO-8601)
            message.messageProperties.headers["execute.pub_ts"] = publishTs.toString()
            message
        }
    }

    @Transactional
    internal suspend fun saveExecutionJobInTransaction(job: ExecutionJob): ExecutionJob {
        return try {
            tracer.withSuspendingSpan("db.save_execution_job", ctx = Dispatchers.IO) {
                executionJobRepository.save(job)
            }
        } catch (e: DataAccessException) {
            logger.error(
                "Failed to save execution job {} to database for user {}: {}",
                job.jobId,
                job.userId,
                e.message,
                e,
            )
            throw e
        }
    }
}
