package org.rucca.snake.common.infra.persistence.repository

import java.util.*
import org.rucca.snake.common.infra.persistence.entity.ExecutionJob
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository

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
     * Retrieves the job IDs of all execution jobs associated with the specified session and requesting user.
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
 * @return `true` if an `ExecutionJob` with the given job ID and user ID exists; otherwise, `false`.
 */
fun existsByJobIdAndUserId(jobId: UUID, userId: Long): Boolean
}
