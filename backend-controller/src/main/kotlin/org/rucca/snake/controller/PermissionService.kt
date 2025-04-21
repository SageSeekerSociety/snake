/*
 *  Description: This file implements the RolePermissionService class.
 *               It provides the permissions for different roles.
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *      nameisyui
 *
 */

package org.rucca.snake.controller

import jakarta.annotation.PostConstruct
import org.rucca.cheese.auth.*
import org.rucca.cheese.auth.error.PermissionDeniedError
import org.rucca.cheese.auth.persistent.IdGetter
import org.rucca.cheese.auth.persistent.IdType
import org.springframework.stereotype.Service

@Service
class PermissionService(private val authorizationService: AuthorizationService) {
    @PostConstruct
    fun initialize() {
        authorizationService.customAuthLogics.register("role-based") {
            userId: IdType,
            action: AuthorizedAction,
            resourceType: String,
            resourceId: IdType?,
            authInfo: Map<String, Any>,
            resourceOwnerIdGetter: IdGetter?,
            customLogicData: Any? ->
            audit(
                userId,
                action,
                resourceType,
                resourceId,
                authInfo,
                resourceOwnerIdGetter,
                customLogicData,
            )
        }
    }

    fun audit(
        userId: IdType,
        action: AuthorizedAction,
        resourceType: String,
        resourceId: IdType?,
        authInfo: Map<String, Any>,
        resourceOwnerIdGetter: IdGetter?,
        customLogicData: Any?,
    ): Boolean {
        val role =
            (customLogicData as? Map<*, *>)?.get("role") as? String
                ?: throw RuntimeException(
                    "Role not found in customLogicData. This is ether a bug or a malicious attack."
                )
        val authorization = getAuthorizationForUserWithRole(userId, role)
        try {
            authorizationService.audit(authorization, action, resourceType, resourceId, authInfo)
            return true
        } catch (e: PermissionDeniedError) {
            return false
        }
    }

    fun getAuthorizationForUserWithRole(userId: IdType, role: String): Authorization {
        return when (role) {
            "standard-user" -> getAuthorizationForStandardUser(userId)
            else -> throw IllegalArgumentException("Role '$role' is not supported")
        }
    }

    fun getAuthorizationForStandardUser(userId: IdType): Authorization {
        return Authorization(
            userId = userId,
            permissions =
                listOf(
                    Permission(
                        authorizedActions = listOf("submit", "execute"),
                        authorizedResource = AuthorizedResource(types = listOf("program")),
                    ),
                    Permission(
                        authorizedActions = listOf("query"),
                        authorizedResource = AuthorizedResource(types = listOf("player", "job")),
                    ),
                ),
        )
    }
}
