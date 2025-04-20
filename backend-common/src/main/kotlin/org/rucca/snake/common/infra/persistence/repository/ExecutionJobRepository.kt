package org.rucca.snake.common.infra.persistence.repository

import java.util.UUID
import org.rucca.snake.common.infra.persistence.entity.ExecutionJob
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface ExecutionJobRepository : JpaRepository<ExecutionJob, UUID> {
    fun findByUserIdOrderBySubmitTimeDesc(userId: Long): List<ExecutionJob>

    fun findAllByJobIdIn(jobIds: Collection<UUID>): List<ExecutionJob>
}
