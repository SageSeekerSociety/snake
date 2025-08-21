package org.rucca.snake.common.utils

import org.slf4j.MDC

/**
 * 在一个包含特定日志上下文的代码块中执行操作。
 * 它能确保上下文信息在块执行后被正确清理。
 *
 * @param context 一个包含上下文键值对的 Map。
 * @param block 需要在包含日志上下文的环境中执行的代码块。
 * @return 代码块的执行结果。
 */
fun <T> withLoggingContext(context: Map<String, String>, block: () -> T): T {
    val previousContext = MDC.getCopyOfContextMap()
    context.forEach { (key, value) -> MDC.put(key, value) }

    try {
        return block()
    } finally {
        if (previousContext == null) {
            MDC.clear()
        } else {
            MDC.setContextMap(previousContext)
        }
    }
}