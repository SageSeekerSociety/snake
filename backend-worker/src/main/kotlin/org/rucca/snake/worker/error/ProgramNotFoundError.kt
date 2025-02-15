package org.rucca.snake.worker.error

import org.rucca.cheese.auth.error.BaseError
import org.rucca.cheese.auth.persistent.IdType
import org.springframework.http.HttpStatus

class ProgramNotFoundError(userId: IdType) :
    BaseError(
        HttpStatus.NOT_FOUND,
        "Program of user $userId cannot be found",
        mapOf("userId" to userId),
    )
