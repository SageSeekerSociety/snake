/*
 *  Description: This file defines the Submitter entity and its repository.
 *               It stores the information of a submitter.
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package org.rucca.snake.controller

import jakarta.persistence.*
import org.hibernate.annotations.DynamicUpdate
import org.hibernate.annotations.SQLRestriction
import org.rucca.cheese.auth.persistent.BaseEntity
import org.rucca.cheese.auth.persistent.IdType
import org.springframework.data.jpa.repository.JpaRepository

@DynamicUpdate
@Entity
@SQLRestriction("deleted_at IS NULL")
class Submitter(
    @JoinColumn(name = "user_id") @ManyToOne(fetch = FetchType.LAZY) var user: User? = null
) : BaseEntity()

interface SubmitterRepository : JpaRepository<Submitter, IdType> {
    fun existsByUserId(userId: IdType): Boolean
}
