package org.rucca.snake.controller.config

import java.time.OffsetDateTime
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@Component
@ConfigurationProperties(prefix = "submission-policy")
data class SubmissionPolicyProperties(
    var systemCloseAt: OffsetDateTime? = null,
    var executeSubmitWhitelist: Set<Long> = emptySet(),
    var executeBatchMaxSize: Int = 30,
)
