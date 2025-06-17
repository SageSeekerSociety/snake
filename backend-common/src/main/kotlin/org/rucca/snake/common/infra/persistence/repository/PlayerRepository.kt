package org.rucca.snake.common.infra.persistence.repository

import org.rucca.snake.common.infra.persistence.entity.Player
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface PlayerRepository : JpaRepository<Player, Int> {
    fun findByIsActiveTrueOrderByNicknameAsc(): List<Player>

    fun findByIsActiveTrueOrderByUpdatedAtDesc(): List<Player>
}
