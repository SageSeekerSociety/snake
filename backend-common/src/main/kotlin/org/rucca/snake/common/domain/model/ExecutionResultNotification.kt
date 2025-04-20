package org.rucca.snake.common.domain.model

import java.time.Instant

data class ExecutionResultNotification(
    val jobId: String,
    val userId: Long,
    val status: JobStatus,
    val timestamp: Instant
)
