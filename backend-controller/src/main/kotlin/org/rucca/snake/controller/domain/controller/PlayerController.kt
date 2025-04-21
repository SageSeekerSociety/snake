package org.rucca.snake.controller.domain.controller

import org.rucca.cheese.auth.annotation.Guard
import org.rucca.snake.controller.domain.model.ApiResponse
import org.rucca.snake.controller.domain.model.PlayerInfoDto
import org.rucca.snake.controller.domain.service.PlayerQueryService
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/players")
class PlayerController(private val playerQueryService: PlayerQueryService) {
    private val logger = LoggerFactory.getLogger(PlayerController::class.java)

    @Guard("query", "player")
    @GetMapping
    suspend fun getActivePlayersList(): ResponseEntity<ApiResponse.Success<List<PlayerInfoDto>>> {
        logger.info("Received request to get active players list.")
        val players =
            try {
                playerQueryService.getActivePlayers()
            } catch (e: Exception) {
                logger.error("Error fetching active players: {}", e.message)
                throw e
            }
        return ResponseEntity.ok(ApiResponse.Success(data = players))
    }
}
