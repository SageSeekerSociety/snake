package org.rucca.snake.common.infra.persistence.entity

import jakarta.persistence.*
import java.time.OffsetDateTime
import org.hibernate.annotations.ColumnDefault
import org.hibernate.annotations.SQLRestriction

@Entity
@Table(
    name = "user_profile",
    schema = "public",
    indexes = [Index(name = "IDX_51cb79b5555effaf7d69ba1cff", columnList = "id", unique = true)],
)
@SQLRestriction("deleted_at IS NULL")
open class UserProfile {
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "user_profile_id_gen")
    @SequenceGenerator(
        name = "user_profile_id_gen",
        sequenceName = "user_profile_id_seq",
        allocationSize = 1,
    )
    @Column(name = "id", nullable = false)
    open var id: Int? = null

    @Column(name = "nickname", nullable = false, length = Integer.MAX_VALUE)
    open var nickname: String? = null

    @Column(name = "intro", nullable = false, length = Integer.MAX_VALUE)
    open var intro: String? = null

    @ColumnDefault("CURRENT_TIMESTAMP")
    @Column(name = "created_at", nullable = false, insertable = false)
    open var createdAt: OffsetDateTime? = null

    @ColumnDefault("CURRENT_TIMESTAMP")
    @Column(name = "updated_at", nullable = false, insertable = false)
    open var updatedAt: OffsetDateTime? = null

    @Column(name = "deleted_at") open var deletedAt: OffsetDateTime? = null

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    open var user: User? = null

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "avatar_id", nullable = false)
    open var avatar: Avatar? = null
}
