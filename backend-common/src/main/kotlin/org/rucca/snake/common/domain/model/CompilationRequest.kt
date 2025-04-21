package org.rucca.snake.common.domain.model

import java.time.Instant

data class CompilationRequest(
    val jobId: String, // 唯一任务 ID (UUID)
    val userId: Long, // 用户 ID
    val sourceCodeRef: String, // 源代码内容 (如果过大，考虑换成 S3 引用)
    val timestamp: Instant = Instant.now(), // 提交时间
)
