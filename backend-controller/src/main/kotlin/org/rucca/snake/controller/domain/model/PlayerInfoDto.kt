package org.rucca.snake.controller.domain.model

import java.time.Instant

data class PlayerInfoDto(
    val userId: Int,
    val username: String,
    val nickname: String,
    val intro: String?,
    val avatarId: Long?,
    val lastUpdate: Instant?, // Or specific compile time?
)
