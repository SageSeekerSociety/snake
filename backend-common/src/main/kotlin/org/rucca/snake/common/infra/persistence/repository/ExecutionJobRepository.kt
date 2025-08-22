package org.rucca.snake.common.infra.persistence.repository

import java.util.*
import org.rucca.snake.common.domain.model.JobStatus
import org.rucca.snake.common.infra.persistence.entity.ExecutionJob
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

@Repository
interface ExecutionJobRepository : JpaRepository<ExecutionJob, UUID> {
    fun findByUserIdOrderBySubmitTimeDesc(userId: Long): List<ExecutionJob>

    /**
     * Retrieves all ExecutionJob entities with job IDs contained in the specified collection.
     *
     * @param jobIds A collection of job UUIDs to filter by.
     * @return A list of ExecutionJob entities matching the provided job IDs.
     */
    fun findAllByJobIdIn(jobIds: Collection<UUID>): List<ExecutionJob>

    /**
     * Retrieves the job IDs of all execution jobs associated with the specified session and
     * requesting user.
     *
     * @param sessionId The unique identifier of the session.
     * @param requestingUserId The ID of the user who requested the jobs.
     * @return A list of job IDs matching the given session and requesting user.
     */
    @Query(
        "SELECT ej.jobId FROM ExecutionJob ej WHERE ej.sessionId = :sessionId AND ej.requestingUserId = :requestingUserId"
    )
    fun findJobIdsBySessionIdAndRequestingUserId(
        sessionId: UUID,
        requestingUserId: Long,
    ): List<UUID>

    /**
     * Determines whether an `ExecutionJob` exists with the specified job ID and user ID.
     *
     * @return `true` if an `ExecutionJob` with the given job ID and user ID exists; otherwise,
     *   `false`.
     */
    fun existsByJobIdAndUserId(jobId: UUID, userId: Long): Boolean

    /**
     * Single-shot final update to avoid read-modify-write. Uses a guard on expected current status
     * (typically PENDING) for idempotency.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(
        """
        UPDATE ExecutionJob ej
        SET ej.status = :status,
            ej.startExecutionTime = CASE WHEN ej.startExecutionTime IS NULL THEN :startTime ELSE ej.startExecutionTime END,
            ej.endExecutionTime = :endTime,
            ej.programOutput = :programOutput,
            ej.cpuTimeSeconds = :cpuTimeSeconds,
            ej.memoryKb = :memoryKb,
            ej.exitCode = :exitCode,
            ej.sandboxLogRef = :sandboxLogRef,
            ej.errorDetails = :errorDetails,
            ej.workerNodeId = :workerNodeId
        WHERE ej.jobId = :jobId AND ej.status = :expectedStatus
        """
    )
    fun updateFinalByIdIfStatus(
        jobId: UUID,
        expectedStatus: JobStatus,
        status: JobStatus,
        startTime: java.time.Instant?,
        endTime: java.time.Instant?,
        programOutput: String?,
        cpuTimeSeconds: Double?,
        memoryKb: Long?,
        exitCode: Int?,
        sandboxLogRef: String?,
        errorDetails: String?,
        workerNodeId: String?,
    ): Int
}
