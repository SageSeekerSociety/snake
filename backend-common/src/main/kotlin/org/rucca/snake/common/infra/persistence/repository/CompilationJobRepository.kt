package org.rucca.snake.common.infra.persistence.repository

import java.util.UUID
import org.rucca.snake.common.infra.persistence.entity.CompilationJob
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface CompilationJobRepository : JpaRepository<CompilationJob, UUID> {
    fun findByUserIdOrderBySubmitTimeDesc(userId: Long): List<CompilationJob>
}
