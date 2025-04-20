package org.rucca.snake.controller.domain.model

import org.rucca.snake.common.domain.model.JobStatus

// Represents a single event pushed via SSE
data class JobSseEvent(
    val jobId: String, // The Job ID this event relates to
    val eventType: String, // e.g., "STATUS_UPDATE", "COMPILER_OUTPUT", "FINAL_RESULT", "ERROR"
    val status: JobStatus? = null, // Current status (if applicable)
    val message: String? = null, // Optional message/details
    val data: Any? = null, // Payload specific to event type (e.g., output lines, final result DTO)
)
