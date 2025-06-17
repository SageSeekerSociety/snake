package org.rucca.snake.controller.domain.service

// common module
// common module
import java.io.InputStream
import java.time.Instant
import java.util.*
import kotlinx.coroutines.Dispatchers
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
import org.slf4j.LoggerFactory
import org.springframework.amqp.AmqpException
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
    @Value("\${amqp.exchange.requests}") private val requestsExchangeName: String,
    @Value("\${amqp.routingkey.compile}") private val compileRoutingKey: String,
    @Value("\${amqp.routingkey.execute}") private val executeRoutingKey: String,
) {
    private val logger = LoggerFactory.getLogger(JobSubmitService::class.java)

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
    ): CompilationJob {
        val jobId = UUID.randomUUID()
        val submitTime = Instant.now()
        // Define the object key for the source code in MinIO
        val sourceCodeObjectKey = "sources/$userId/$jobId/source.cpp" // Example key structure

        // 1. Upload Source Code to MinIO first
        val uploadSuccess =
            try {
                withContext(Dispatchers.IO) {
                    minioService.uploadStream(
                        objectKey = sourceCodeObjectKey,
                        inputStream = sourceCodeStream,
                        size = sourceCodeSize, // Provide size
                        contentType = "text/plain", // Or "text/x-c++src" etc.
                    )
                }
            } catch (e: Exception) {
                logger.error(
                    "Failed to upload source code to MinIO for potential job {} (user {}): {}",
                    jobId,
                    userId,
                    e.message,
                    e,
                )
                // If upload fails, we cannot proceed. Throw an exception that leads to API error
                // response.
                throw RuntimeException("Failed to store source code before submitting job", e)
            } finally {
                // Important: Ensure the stream provided by MultipartFile is closed if necessary
                // Spring might handle this, but defensive closing is good if unsure.
                try {
                    withContext(Dispatchers.IO) { sourceCodeStream.close() }
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
                withContext(Dispatchers.IO) { compilationJobRepository.save(compilationJob) }
            } catch (e: DataAccessException) {
                logger.error(
                    "Failed to save compilation job {} to database for user {}: {}",
                    jobId,
                    userId,
                    e.message,
                )
                // Consider deleting the uploaded source from MinIO if DB save fails (compensation
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
            withContext(Dispatchers.IO) {
                rabbitTemplate.convertAndSend(requestsExchangeName, compileRoutingKey, request) {
                    message ->
                    message.messageProperties.correlationId = jobId.toString()
                    message.messageProperties.headers[AmqpConstants.HEADER_MESSAGE_TYPE] =
                        AmqpConstants.MESSAGE_TYPE_COMPILE
                    message
                }
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

        return savedJob
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
    ): Map<String, Result<String>> {
        val results = mutableMapOf<String, Result<String>>()
        val submittedJobIds = mutableListOf<UUID>()

        requests.forEachIndexed { index, item ->
            // Use clientRequestId if available, otherwise generate a temporary key for the result
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
                    )
                }
                results[resultKey] = Result.failure(e) // Return failure with exception
            }
        }
        return results
    }

    /**
     * Internal transactional method to submit a single execution task. Separated to allow
     * individual transaction management for batch items.
     *
     * @param item The single execution request item.
     * @return The created ExecutionJob entity.
     * @throws DataAccessException, AmqpException, RuntimeException on failure.
     */
    @Transactional // Each execution submission gets its own transaction
    internal suspend fun submitSingleExecutionInternal(
        item: BatchExecutionItem,
        finalSessionId: String,
        requestingUserId: Long,
    ): ExecutionJob {
        val jobId = UUID.randomUUID()
        val submitTime = Instant.now()

        // 1. Create Job Entity
        val executionJob =
            ExecutionJob(
                jobId = jobId,
                userId = item.userId,
                status = JobStatus.PENDING,
                submitTime = submitTime,
                clientRequestId = item.clientRequestId,
                sessionId = UUID.fromString(finalSessionId),
            )

        // 2. Save to Database
        val savedJob: ExecutionJob =
            try {
                withContext(Dispatchers.IO) { executionJobRepository.save(executionJob) }
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
                userId = item.userId,
                inputData = item.inputData, // Consider reference if large
                cpuTimeLimitSeconds = item.cpuTimeLimitSeconds,
                memoryLimitKb = item.memoryLimitKb,
                wallTimeLimitSeconds = item.wallTimeLimitSeconds,
                clientRequestId = item.clientRequestId, // Pass through
                timestamp = submitTime,
                // New fields
                sessionId = finalSessionId,
                tickNumber = item.tickNumber,
                currentUserId = requestingUserId, // User who made the HTTP call
            )

        // 4. Send to RabbitMQ
        try {
            withContext(Dispatchers.IO) {
                rabbitTemplate.convertAndSend(requestsExchangeName, executeRoutingKey, request) {
                    message ->
                    message.messageProperties.correlationId = jobId.toString()
                    message.messageProperties.headers[AmqpConstants.HEADER_MESSAGE_TYPE] =
                        AmqpConstants.MESSAGE_TYPE_EXECUTE
                    // Optional: Pass clientRequestId if worker needs it
                    // item.clientRequestId?.let {
                    // message.messageProperties.headers["clientRequestId"] = it }
                    message
                }
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
