package org.rucca.snake.controller.infra.throttle

/**
 * 当速率限制被触发时抛出的自定义异常。
 *
 * @param nanosToWaitForRefill 需要等待多少纳秒才能有新的令牌。
 */
class RateLimitExceededException(message: String, val nanosToWaitForRefill: Long) :
    RuntimeException(message)
