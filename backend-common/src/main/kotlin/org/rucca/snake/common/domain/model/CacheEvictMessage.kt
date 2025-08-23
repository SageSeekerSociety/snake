package org.rucca.snake.common.domain.model

import java.time.Instant

data class CacheEvictMessage(val objectKey: String, val timestamp: Instant = Instant.now())
