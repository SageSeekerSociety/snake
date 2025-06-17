package org.rucca.snake.common.domain.model

import java.time.Instant

data class ExecutionResultNotification(
    val jobId: String,
    val userId: Long,
    val status: JobStatus,
    val timestamp: Instant,
    val newMemoryData: String? = null,
    val sessionId: String,
    val tickNumber: Int,
    val action: String? = null,
)
