package org.rucca.snake.controller.domain.model

data class BatchExecutionItem(
    val userId: Long, // Which user's compiled program to run
    val inputData: String, // The input data for this specific execution run
    val cpuTimeLimitSeconds: Double, // CPU time limit in seconds
    val memoryLimitKb: Long, // Memory limit in Kilobytes
    val wallTimeLimitSeconds: Long, // Wall clock time limit in seconds

    // An optional ID provided by the client (e.g., frontend)
    // to correlate this specific item back in the batch response.
    // If not provided, the backend might generate one or use index.
    val clientRequestId: String? = null,
)
