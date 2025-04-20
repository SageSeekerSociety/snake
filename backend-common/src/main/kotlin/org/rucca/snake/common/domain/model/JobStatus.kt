package org.rucca.snake.common.domain.model

enum class JobStatus {
    PENDING,
    SUBMITTED, // Producer 端状态
    RECEIVED,
    COMPILING,
    RUNNING, // Worker 端中间状态
    SUCCESS,
    FAILED, // 编译结果状态
    TLE,
    MLE,
    RE,
    OLE, // 执行结果状态 (Time Limit, Memory Limit, Runtime Error, Output Limit)
    ERROR, // Worker 内部错误或无法处理
}
