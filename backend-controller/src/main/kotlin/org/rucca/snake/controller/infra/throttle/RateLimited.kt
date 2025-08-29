package org.rucca.snake.controller.infra.throttle

/** @RateLimited 注解的容器，使其可以重复使用。 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class RateLimits(val value: Array<RateLimited>)

/** 声明一个可重复的、基于SpEL的速率限制规则。 */
@JvmRepeatable(RateLimits::class)
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class RateLimited(
    /** The name of the rate limiting policy defined in application.yml. */
    val value: String
)
