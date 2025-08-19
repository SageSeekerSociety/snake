package org.rucca.snake.controller.domain.service

import java.util.*
import kotlinx.coroutines.*
import org.rucca.snake.common.infra.persistence.repository.CompilationJobRepository
import org.rucca.snake.common.infra.persistence.repository.ExecutionJobRepository
import org.rucca.snake.controller.domain.model.BatchQueryResultItem
import org.rucca.snake.controller.domain.model.CompilationJobResultDto
import org.rucca.snake.controller.domain.model.ExecutionJobResultDto
import org.rucca.snake.controller.domain.model.JobResultDto
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.stereotype.Service

@Service
class JobQueryService(
    private val compilationJobRepository: CompilationJobRepository,
    private val executionJobRepository: ExecutionJobRepository,
) {
    private val logger = LoggerFactory.getLogger(JobQueryService::class.java)

    /**
     * Retrieves a list of execution job IDs associated with the specified session and requesting
     * user.
     *
     * @param sessionId The UUID of the session to query.
     * @param requestingUserId The ID of the user requesting the job IDs.
     * @return A list of job UUIDs for the given session and user, or an empty list if none are
     *   found.
     */
    fun getJobIdsBySessionId(sessionId: UUID, requestingUserId: Long): List<UUID> {
        val jobIds =
            executionJobRepository.findJobIdsBySessionIdAndRequestingUserId(
                sessionId,
                requestingUserId,
            )
        if (jobIds.isEmpty()) {
            return emptyList()
        }
        return jobIds
    }

    /**
     * Finds a job (either compilation or execution) by its UUID and returns its result DTO.
     * Searches Execution jobs first, then Compilation jobs.
     *
     * @param jobId The UUID of the job to find.
     * @return A JobResultDto (either CompilationJobResultDto or ExecutionJobResultDto) if found,
     *   otherwise null.
     * @throws DataAccessException if there's an issue accessing the database.
     */
    suspend fun getJobStatusAndResult(jobId: UUID): JobResultDto? {
        return withContext(Dispatchers.IO) { // Perform DB lookups on IO dispatcher
            try {
                // 1. Try finding in Execution Jobs
                val executionJob = executionJobRepository.findById(jobId).orElse(null)
                if (executionJob != null) {
                    logger.debug("Found execution job with ID: {}", jobId)
                    // Map entity to DTO
                    return@withContext ExecutionJobResultDto(
                        jobId = executionJob.jobId,
                        userId = executionJob.userId,
                        status = executionJob.status,
                        submitTime = executionJob.submitTime,
                        startTime = executionJob.startExecutionTime,
                        endTime = executionJob.endExecutionTime,
                        errorDetails = executionJob.errorDetails,
                        workerNodeId = executionJob.workerNodeId,
                        programOutput =
                            executionJob
                                .programOutput, // Consider truncation/ref logic here if needed
                        cpuTimeSeconds = executionJob.cpuTimeSeconds,
                        memoryKb = executionJob.memoryKb,
                        exitCode = executionJob.exitCode,
                        sandboxLogRef = executionJob.sandboxLogRef,
                        clientRequestId = executionJob.clientRequestId,
                        sessionId = executionJob.sessionId?.toString(),
                        tickNumber = executionJob.tickNumber,
                    )
                }

                // 2. If not found, try finding in Compilation Jobs
                val compilationJob = compilationJobRepository.findById(jobId).orElse(null)
                if (compilationJob != null) {
                    logger.debug("Found compilation job with ID: {}", jobId)
                    // Map entity to DTO
                    return@withContext CompilationJobResultDto(
                        jobId = compilationJob.jobId,
                        userId = compilationJob.userId,
                        status = compilationJob.status,
                        submitTime = compilationJob.submitTime,
                        startTime = compilationJob.startCompileTime,
                        endTime = compilationJob.endCompileTime,
                        errorDetails = compilationJob.errorDetails,
                        workerNodeId = compilationJob.workerNodeId,
                        compilerOutput =
                            compilationJob.compilerOutput, // Consider truncation if needed
                        programStorageRef = compilationJob.programStorageRef,
                    )
                }

                // 3. Not found in either table
                logger.info(
                    "Job with ID {} not found in either execution or compilation tables.",
                    jobId,
                )
                return@withContext null
            } catch (e: DataAccessException) {
                logger.error("Database error while querying for job ID {}: {}", jobId, e.message)
                throw e // Re-throw database exceptions to be handled by controller advice
            } catch (e: Exception) {
                logger.error(
                    "Unexpected error while querying for job ID {}: {}",
                    jobId,
                    e.message,
                    e,
                )
                throw RuntimeException(
                    "Unexpected error querying job $jobId",
                    e,
                ) // Wrap unexpected errors
            }
        }
    }

    /**
     * Finds multiple jobs (compilation or execution) by their UUIDs. Performs lookups in parallel
     * for efficiency.
     *
     * @param jobIds A collection of job UUIDs to query.
     * @return A List of BatchQueryResultItem, containing the original requested jobId and the found
     *   JobResultDto (or null). The order might not match the input order unless explicitly
     *   handled.
     */
    suspend fun getBatchJobStatusAndResults(jobIds: Collection<UUID>): List<BatchQueryResultItem> {
        if (jobIds.isEmpty()) {
            return emptyList()
        }

        // Use coroutineScope to launch parallel lookups
        return coroutineScope {
            // Create deferred lookups for each jobId
            val deferredResults: List<Deferred<BatchQueryResultItem>> =
                jobIds.map { jobId ->
                    async(Dispatchers.IO) { // Launch each lookup on IO dispatcher
                        val resultDto =
                            try {
                                getJobStatusAndResult(jobId) // Reuse the single job lookup logic
                            } catch (e: Exception) {
                                // Log error but don't fail the whole batch
                                logger.error(
                                    "Failed to query job {} during batch lookup: {}",
                                    jobId,
                                    e.message,
                                )
                                null // Indicate query failure for this specific job
                            }
                        BatchQueryResultItem(queryJobId = jobId.toString(), result = resultDto)
                    }
                }
            // Await all lookups to complete
            deferredResults.awaitAll()
        }
    }

    /** Helper function to convert String IDs from API to UUIDs, handling potential errors. */
    fun parseJobIds(idStrings: Collection<String>): List<UUID> {
        return idStrings.mapNotNull { strId ->
            try {
                UUID.fromString(strId)
            } catch (e: IllegalArgumentException) {
                logger.warn("Invalid UUID format received in batch query: {}", strId)
                null // Skip invalid IDs
            }
        }
    }

    fun isOwnerOfExecutionJob(jobId: UUID, userId: Long): Boolean {
        return executionJobRepository.existsByJobIdAndUserId(jobId, userId)
    }

    fun isOwnerOfCompilationJob(jobId: UUID, userId: Long): Boolean {
        return compilationJobRepository.existsByJobIdAndUserId(jobId, userId)
    }
}
