package org.rucca.snake.common.infra.persistence.repository

import org.rucca.snake.common.infra.persistence.entity.User
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface UserRepository : JpaRepository<User, Int> {}