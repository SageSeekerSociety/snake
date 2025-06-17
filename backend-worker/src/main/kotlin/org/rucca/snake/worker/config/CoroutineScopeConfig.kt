package org.rucca.snake.worker.config

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class CoroutineScopeConfig {

    @Bean("applicationCoroutineScope")
    fun applicationCoroutineScope(): CoroutineScope {
        // SupervisorJob 确保一个子协程的失败不会影响其他子协程
        // Dispatchers.Default 适用于 CPU 密集型操作。
        // TaskProcessor 中的初始消息解析（如 JSON 解析）可能属于此类。
        // 后续的服务调用已通过 withContext(Dispatchers.IO) 切换到 IO 调度器。
        return CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
