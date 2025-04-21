package org.rucca.snake.common.domain.model

import java.time.Instant

data class ExecutionRequest(
    val jobId: String, // 唯一任务 ID (UUID)
    val userId: Long, // 用户 ID
    val inputData: String, // 输入数据 (如果过大，考虑换成 S3 引用)
    val cpuTimeLimitSeconds: Double, // CPU 时间限制 (秒)
    val memoryLimitKb: Long, // 内存限制 (KB)
    val wallTimeLimitSeconds: Long, // 墙上时间限制 (秒)
    val clientRequestId: String? = null,
    val timestamp: Instant = Instant.now(), // 提交时间
)
