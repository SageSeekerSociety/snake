package org.rucca.snake.controller.infra.throttle

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import java.time.temporal.ChronoUnit
import org.intellij.lang.annotations.Language
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import org.springframework.validation.annotation.Validated

@Component
@Validated
@ConfigurationProperties(prefix = "rate-limiter")
data class RateLimiterProperties(@field:Valid var policies: Map<String, Policy> = emptyMap())

data class Policy(
    @Positive var capacity: Long,
    @Positive var refillRate: Long,
    @Positive var refillPeriod: Long = 1,
    var refillUnit: ChronoUnit = ChronoUnit.MINUTES,
    @NotBlank @Language("SpEL") var keyExpression: String,
    @NotBlank @Language("SpEL") var tokensExpression: String = "1",
)
