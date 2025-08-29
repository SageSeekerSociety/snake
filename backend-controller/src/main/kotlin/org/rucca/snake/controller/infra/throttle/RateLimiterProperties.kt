package org.rucca.snake.controller.infra.throttle

import java.time.temporal.ChronoUnit
import org.intellij.lang.annotations.Language
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@Component
@ConfigurationProperties(prefix = "rate-limiter")
data class RateLimiterProperties(var policies: Map<String, Policy> = emptyMap())

data class Policy(
    var capacity: Long,
    var refillRate: Long,
    var refillPeriod: Long = 1,
    var refillUnit: ChronoUnit = ChronoUnit.MINUTES,
    @Language("SpEL") var keyExpression: String,
    @Language("SpEL") var tokensExpression: String = "1",
)
