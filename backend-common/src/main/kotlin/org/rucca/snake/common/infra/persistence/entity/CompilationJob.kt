package org.rucca.snake.common.infra.persistence.entity

import jakarta.persistence.*
import java.time.Instant
import java.util.*
import org.rucca.snake.common.domain.model.JobStatus
import org.rucca.snake.common.utils.UuidV7

@Entity
@Table(
    name = "compilation_jobs",
    indexes =
        [
            Index(name = "idx_compilation_jobs_user_id", columnList = "userId"),
            Index(name = "idx_compilation_jobs_status", columnList = "status"),
            Index(name = "idx_compilation_jobs_submit_time", columnList = "submitTime"),
            // Mirror Flyway V6 composite index (note: JPA cannot express DESC here)
            Index(name = "idx_compilation_jobs_user_submit_time", columnList = "userId, submitTime"),
        ],
)
data class CompilationJob(
    @Id var jobId: UUID = UuidV7.generate(),
    @Column(nullable = false) var userId: Long = 0,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: JobStatus = JobStatus.PENDING,
    @Column(nullable = false) var submitTime: Instant = Instant.now(),
    @Column(length = 1024, nullable = false) // Make reference mandatory for compilation
    var sourceCodeRef: String = "", // Reference to source code (e.g., MinIO key)
    var receiveTime: Instant? = null,
    var startCompileTime: Instant? = null,
    var endCompileTime: Instant? = null,
    @Lob @Column(columnDefinition = "TEXT") var compilerOutput: String? = null,
    @Column(length = 1024) var programStorageRef: String? = null,
    @Column(length = 255) var workerNodeId: String? = null,
    @Lob @Column(columnDefinition = "TEXT") var errorDetails: String? = null,
)
