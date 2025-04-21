package org.rucca.snake.controller.domain.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.rucca.snake.common.infra.persistence.repository.PlayerRepository
import org.rucca.snake.common.infra.persistence.repository.UserRepository
import org.rucca.snake.controller.domain.model.PlayerInfoDto
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class PlayerQueryService(
    private val playerRepository: PlayerRepository,
    private val userRepository: UserRepository,
    private val userService: UserService,
) {
    private val logger = LoggerFactory.getLogger(PlayerQueryService::class.java)

    /**
     * Retrieves a list of all active players.
     *
     * @return List of PlayerInfoDto.
     */
    suspend fun getActivePlayers(): List<PlayerInfoDto> {
        return withContext(Dispatchers.IO) {
            try {
                logger.debug("Querying active players...")
                // Fetch active players, ordered as needed (e.g., by nickname)
                val players = playerRepository.findByIsActiveTrueOrderByNicknameAsc()
                logger.info("Found {} active players.", players.size)
                val users = userRepository.findAllById(players.map { it.userId })
                val userDtos = userService.convertUsersToDto(users)
                // Map Player entities to PlayerInfoDto
                players.map { player ->
                    val user = userDtos[player.userId.toLong()]
                    if (user == null) {
                        logger.error(
                            "User not found for player with userId {}. This should not happen.",
                            player.userId,
                        )
                        throw IllegalStateException(
                            "User not found for player with userId ${player.userId}."
                        )
                    }
                    PlayerInfoDto(
                        userId = player.userId,
                        username = user.username,
                        nickname = user.nickname,
                        intro = user.intro,
                        avatarId = user.avatarId,
                        lastUpdate = player.updatedAt, // Or lastSuccessfulCompileTime?
                    )
                }
            } catch (e: Exception) {
                logger.error("Failed to retrieve active players from database: {}", e.message, e)
                // Return empty list or throw a custom exception
                emptyList()
            }
        }
    }
}
