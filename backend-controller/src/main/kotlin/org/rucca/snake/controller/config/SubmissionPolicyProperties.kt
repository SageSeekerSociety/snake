package org.rucca.snake.controller.config

import java.time.Instant
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@Component
@ConfigurationProperties(prefix = "submission-policy")
data class SubmissionPolicyProperties(
    var systemCloseAt: Instant? = null,
    var executeSubmitWhitelist: Set<Long> = emptySet(),
)
