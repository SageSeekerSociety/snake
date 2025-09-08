package org.rucca.snake.common.infra.persistence.repository

import java.util.UUID
import org.rucca.snake.common.domain.model.JobStatus
import org.rucca.snake.common.infra.persistence.entity.CompilationJob
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

@Repository
interface CompilationJobRepository : JpaRepository<CompilationJob, UUID> {
    fun findByUserIdOrderBySubmitTimeDesc(userId: Long): List<CompilationJob>

    fun existsByJobIdAndUserId(jobId: UUID, userId: Long): Boolean

    /** Single-shot final update to avoid read-modify-write. Guarded by expected status. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(
        """
         UPDATE CompilationJob cj
         SET cj.status = :status,
             cj.startCompileTime = CASE WHEN cj.startCompileTime IS NULL THEN :startTime ELSE cj.startCompileTime END,
             cj.endCompileTime = :endTime,
             cj.compilerOutput = :compilerOutput,
             cj.programStorageRef = :programStorageRef,
             cj.errorDetails = :errorDetails,
             cj.workerNodeId = :workerNodeId
         WHERE cj.jobId = :jobId AND cj.status = :expectedStatus
         """
    )
    fun updateFinalByIdIfStatus(
        jobId: UUID,
        expectedStatus: JobStatus,
        status: JobStatus,
        startTime: java.time.Instant?,
        endTime: java.time.Instant?,
        compilerOutput: String?,
        programStorageRef: String?,
        errorDetails: String?,
        workerNodeId: String?,
    ): Int

    interface LatestSuccessSourceProjection {
        fun getUserId(): Long

        fun getSourceCodeRef(): String
    }

    /**
     * Fetch the latest successful compilation source per user using PostgreSQL DISTINCT ON. Orders
     * by most recent end_compile_time (fallback by submit_time when null).
     */
    @Query(
        value =
            """
            SELECT DISTINCT ON (user_id)
                user_id AS userId,
                source_code_ref AS sourceCodeRef
            FROM compilation_jobs
            WHERE status = 'SUCCESS'
            ORDER BY user_id, end_compile_time DESC NULLS LAST, submit_time DESC
            """,
        nativeQuery = true,
    )
    fun findLatestSuccessSourcePerUser(): List<LatestSuccessSourceProjection>
}
