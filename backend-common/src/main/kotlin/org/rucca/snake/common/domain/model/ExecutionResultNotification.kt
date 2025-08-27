package org.rucca.snake.common.domain.model

import java.time.Instant

data class ExecutionResultNotification(
    val jobId: String,
    val userId: Long,
    val status: JobStatus,
    val sessionId: String,
    val tickNumber: Int,
    val cpuTimeSeconds: Double?,
    val memoryKb: Long?,
    val exitCode: Int?,
    val action: String?,
    val programStderr: String?,
    val newMemoryData: String?,
    val errorDetails: String?,
    val clientRequestId: String?,
    val workerNodeId: String?,
    val submitTime: Instant,
    val startTime: Instant,
    val endTime: Instant,
)
