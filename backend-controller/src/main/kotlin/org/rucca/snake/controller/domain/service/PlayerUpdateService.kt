package org.rucca.snake.controller.domain.service

import io.opentelemetry.api.OpenTelemetry
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import org.rucca.snake.common.domain.model.JobStatus
import org.rucca.snake.common.infra.persistence.entity.Player
import org.rucca.snake.common.infra.persistence.repository.PlayerRepository
import org.rucca.snake.common.infra.persistence.repository.UserProfileRepository
import org.rucca.snake.common.utils.withSuspendingSpan
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class PlayerUpdateService(
    private val playerRepository: PlayerRepository,
    private val userProfileRepository: UserProfileRepository,
    openTelemetry: OpenTelemetry,
) {
    private val tracer = openTelemetry.getTracer(PlayerUpdateService::class.java.name)

    private val logger = LoggerFactory.getLogger(PlayerUpdateService::class.java)

    /** Updates or creates the Player record upon successful code compilation. */
    @Transactional
    suspend fun updatePlayerOnCompileSuccess(
        userId: Int, // Assuming User ID is Int
        jobId: UUID,
        compiledProgramRef: String,
        compileTime: Instant,
    ) =
        tracer.withSuspendingSpan("db.update_player_on_compile", ctx = Dispatchers.IO) {
            try {
                // 1. Fetch UserProfile and Avatar data (needed for denormalized fields)
                val userProfile = userProfileRepository.findByUserId(userId)

                if (userProfile == null) {
                    logger.warn(
                        "Cannot update player record: UserProfile not found for userId {}",
                        userId,
                    )
                    return@withSuspendingSpan
                }

                // 2. Find existing player or create new one
                val player =
                    playerRepository.findById(userId).orElse(Player(userId = userId)).apply {
                        this.nickname = userProfile.nickname ?: "N/A"
                        this.lastSuccessfulCompileJobId = jobId
                        this.lastSuccessfulCompileTime = compileTime
                        this.compiledProgramRef =
                            compiledProgramRef // Update the active program reference
                        this.isActive = true // Ensure player is active
                        // Let @PreUpdate handle updatedAt, @PrePersist handles createdAt
                    }

                // 3. Save (Upsert) Player record
                playerRepository.save(player)
                logger.info(
                    "Upserted Player record for userId {} due to successful compilation job {}",
                    userId,
                    jobId,
                )
            } catch (e: Exception) {
                logger.error(
                    "Failed to update player record for userId {} after compile success (jobId {}): {}",
                    userId,
                    jobId,
                    e.message,
                    e,
                )
                // Log the error, but don't let it fail the listener (message already ACKed by
                // worker)
                // Consider adding specific monitoring or retry for this failure.
            }
        }

    // Optional: Handle other notifications, e.g., disable player on repeated errors?
    suspend fun handleExecutionResult(userId: Int, status: JobStatus) {
        // Placeholder for potential future logic based on execution results
        logger.debug("Received execution result for user {}: {}", userId, status)
    }
}
