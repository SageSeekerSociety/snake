package org.rucca.snake.controller.domain.model

import java.time.Instant
import java.util.*
import org.rucca.snake.common.domain.model.JobStatus

interface JobResultDto {
    val jobId: UUID
    val userId: Long
    val status: JobStatus
    val submitTime: Instant
    val startTime: Instant?
    val endTime: Instant?
    val errorDetails: String?
    val workerNodeId: String?
}

// DTO for Compilation Job Result
data class CompilationJobResultDto(
    override val jobId: UUID,
    override val userId: Long,
    override val status: JobStatus,
    override val submitTime: Instant,
    override val startTime: Instant?,
    override val endTime: Instant?,
    override val errorDetails: String?,
    override val workerNodeId: String?,
    val compilerOutput: String?, // Specific to compilation
    val programStorageRef: String?, // Specific to compilation
) : JobResultDto

// DTO for Execution Job Result
data class ExecutionJobResultDto(
    override val jobId: UUID,
    override val userId: Long,
    override val status: JobStatus,
    override val submitTime: Instant,
    override val startTime: Instant?,
    override val endTime: Instant?,
    override val errorDetails: String?,
    override val workerNodeId: String?,
    val programOutput: String?, // Specific to execution (consider truncation/ref if large)
    val programStderr: String?, // Stderr output from program
    val cpuTimeSeconds: Double?, // Specific to execution
    val memoryKb: Long?, // Specific to execution
    val exitCode: Int?, // Specific to execution
    val clientRequestId: String?,
    val sessionId: String?,
    val tickNumber: Int?,
) : JobResultDto
