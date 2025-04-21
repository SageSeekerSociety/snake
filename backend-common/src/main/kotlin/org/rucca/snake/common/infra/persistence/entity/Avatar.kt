package org.rucca.snake.common.infra.persistence.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.ColumnDefault

@Entity
@Table(name = "avatar", schema = "public")
open class Avatar {
    @Id
    @ColumnDefault("nextval('avatar_id_seq')")
    @Column(name = "id", nullable = false)
    open var id: Int? = null
}