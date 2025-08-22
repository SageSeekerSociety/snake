package org.rucca.snake.common.infra.persistence.entity

import jakarta.persistence.*
import java.time.Instant
import java.util.*
import org.rucca.snake.common.utils.UuidV7
import org.rucca.snake.common.domain.model.JobStatus

@Entity
@Table(
    name = "execution_jobs",
    indexes =
        [
            Index(name = "idx_execution_jobs_user_id", columnList = "userId"),
            Index(name = "idx_execution_jobs_status", columnList = "status"),
            Index(name = "idx_execution_jobs_submit_time", columnList = "submitTime"),
            Index(name = "idx_execution_jobs_session_user", columnList = "sessionId, userId"),
            Index(
                name = "idx_execution_jobs_session_requesting_user_tick",
                columnList = "sessionId, requestingUserId, tickNumber",
            ),
        ],
)
data class ExecutionJob(
    @Id var jobId: UUID = UuidV7.generate(),
    @Column(nullable = false) var userId: Long = 0,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: JobStatus = JobStatus.PENDING,
    @Column(nullable = false) var submitTime: Instant = Instant.now(),
    var receiveTime: Instant? = null,
    var startExecutionTime: Instant? = null,
    var endExecutionTime: Instant? = null,
    @Lob @Column(columnDefinition = "TEXT") var programOutput: String? = null,
    var cpuTimeSeconds: Double? = null,
    var memoryKb: Long? = null,
    var exitCode: Int? = null,
    @Column(length = 1024) var sandboxLogRef: String? = null,
    @Column(length = 255) var workerNodeId: String? = null,
    @Column(length = 255) var clientRequestId: String? = null,
    @Lob @Column(columnDefinition = "TEXT") var errorDetails: String? = null,
    @Column(columnDefinition = "uuid") var sessionId: UUID? = null,
    var tickNumber: Int = 0, // Tick number for the job, useful for tracking in game-like scenarios
    var requestingUserId: Long? = null, // User who requested the job, if different from owner
)
