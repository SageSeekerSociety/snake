package org.rucca.snake.common.infra.persistence.entity

import jakarta.persistence.*
import java.time.OffsetDateTime
import org.hibernate.annotations.ColumnDefault
import org.hibernate.annotations.SQLRestriction

@Entity
@Table(
    name = "\"user\"",
    schema = "public",
    indexes =
        [
            Index(name = "IDX_78a916df40e02a9deb1c4b75ed", columnList = "username", unique = true),
            Index(name = "IDX_e12875dfb3b1d92d7d7c5377e2", columnList = "email", unique = true),
        ],
)
@SQLRestriction("deleted_at IS NULL")
open class User {
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "user_id_gen")
    @SequenceGenerator(name = "user_id_gen", sequenceName = "user_id_seq", allocationSize = 1)
    @Column(name = "id", nullable = false)
    open var id: Int? = null

    @Column(name = "username", nullable = false, length = Integer.MAX_VALUE)
    open var username: String? = null

    @Column(name = "hashed_password", nullable = true, length = Integer.MAX_VALUE)
    open var hashedPassword: String? = null

    @Column(name = "email", nullable = false, length = Integer.MAX_VALUE)
    open var email: String? = null

    @ColumnDefault("CURRENT_TIMESTAMP")
    @Column(name = "created_at", nullable = false, insertable = false)
    open var createdAt: OffsetDateTime? = null

    @ColumnDefault("CURRENT_TIMESTAMP")
    @Column(name = "updated_at", nullable = false, insertable = false)
    open var updatedAt: OffsetDateTime? = null

    @Column(name = "deleted_at") open var deletedAt: OffsetDateTime? = null
}
