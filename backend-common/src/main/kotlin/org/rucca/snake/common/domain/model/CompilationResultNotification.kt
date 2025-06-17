package org.rucca.snake.common.domain.model

import java.time.Instant

data class CompilationResultNotification(
    val jobId: String,
    val userId: Long,
    val status: JobStatus,
    val compiledProgramRef: String?,
    val timestamp: Instant,
)
