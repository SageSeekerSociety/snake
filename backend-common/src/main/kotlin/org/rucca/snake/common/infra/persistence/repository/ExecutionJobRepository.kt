package org.rucca.snake.common.infra.persistence.repository

import java.util.*
import org.rucca.snake.common.infra.persistence.entity.ExecutionJob
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository

@Repository
interface ExecutionJobRepository : JpaRepository<ExecutionJob, UUID> {
    fun findByUserIdOrderBySubmitTimeDesc(userId: Long): List<ExecutionJob>

    fun findAllByJobIdIn(jobIds: Collection<UUID>): List<ExecutionJob>

    @Query(
        "SELECT ej.jobId FROM ExecutionJob ej WHERE ej.sessionId = :sessionId AND ej.requestingUserId = :requestingUserId"
    )
    fun findJobIdsBySessionIdAndRequestingUserId(
        sessionId: UUID,
        requestingUserId: Long,
    ): List<UUID>

    fun existsByJobIdAndUserId(jobId: UUID, userId: Long): Boolean
}
