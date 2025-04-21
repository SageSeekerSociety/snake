package org.rucca.snake.controller.domain.model

// Data class for Batch query response item
data class BatchQueryResultItem(
    val queryJobId: String, // The jobId requested by the client
    val result: JobResultDto?, // The actual result DTO, or null if not found/error
    // Add error field if query itself failed for this id?
    // val queryError: String? = null
)
