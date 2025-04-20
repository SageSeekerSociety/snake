/*
 *  Description: This file implements the TopicService class.
 *               It is responsible for providing user's DTO.
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *      nameisyui
 *
 */

package org.rucca.snake.controller.domain.service

import org.rucca.snake.common.infra.persistence.entity.User
import org.rucca.snake.common.infra.persistence.repository.UserProfileRepository
import org.rucca.snake.common.infra.persistence.repository.UserRepository
import org.rucca.snake.controller.model.UserDTO
import org.springframework.stereotype.Service

@Service
class UserService(
    private val userRepository: UserRepository,
    private val userProfileRepository: UserProfileRepository,
) {
    /**
     * Batch converts User entities to DTOs with optimized query performance.
     *
     * Instead of N+1 queries (one for each user plus one for each profile), this method performs a
     * single batch query to fetch all profiles at once, reducing database roundtrips significantly
     * for large sets.
     */
    fun convertUsersToDto(users: List<User>): Map<Long, UserDTO> {
        if (users.isEmpty()) {
            return emptyMap()
        }

        val userIds = users.mapNotNull { it.id }
        val profiles = userProfileRepository.findAllByUserIdIn(userIds)
        val profileMap = profiles.associateBy { it.user!!.id!! }
        return users
            .mapNotNull { user ->
                val userId = user.id ?: return@mapNotNull null
                val profile = profileMap[userId] ?: return@mapNotNull null

                val dto =
                    UserDTO(
                        avatarId = profile.avatar!!.id!!.toLong(),
                        id = userId.toLong(),
                        intro = profile.intro!!,
                        nickname = profile.nickname!!,
                        username = user.username!!,
                    )

                userId.toLong() to dto
            }
            .toMap()
    }
}
