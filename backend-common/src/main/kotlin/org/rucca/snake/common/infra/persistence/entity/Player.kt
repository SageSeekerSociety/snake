package org.rucca.snake.common.infra.persistence.entity

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(
    name = "players",
    indexes = [
        Index(name = "idx_players_user_id", columnList = "user_id"),
    ]
)
data class Player(
    @Id
    // No @GeneratedValue if user_id is the PK and comes from User
    @Column(name = "user_id", nullable = false)
    var userId: Int = 0, // Corresponds to User.id (which is Int?)

    @Column(nullable = false, length = 255)
    var nickname: String = "", // Denormalized nickname

    @Column(name = "last_successful_compile_job_id")
    var lastSuccessfulCompileJobId: UUID? = null, // Link to the compilation job

    @Column(name = "last_successful_compile_time")
    var lastSuccessfulCompileTime: Instant? = null, // Timestamp of last successful compile

    @Column(name = "compiled_program_ref", nullable = false, length = 1024)
    var compiledProgramRef: String = "", // Reference (e.g., MinIO key) to the active program

    @Column(name = "is_active", nullable = false)
    var isActive: Boolean = true,

    @Column(name = "created_at", nullable = false, updatable = false) // Typically only set on creation
    var createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()
) {
    @PrePersist
    fun onPrePersist() {
        val now = Instant.now()
        createdAt = now
        updatedAt = now
    }

    @PreUpdate
    fun onPreUpdate() {
        updatedAt = Instant.now()
    }
}
