package org.rucca.snake.worker.error

import org.rucca.cheese.auth.error.BaseError
import org.springframework.http.HttpStatus

class ExecutionError(exitCode: Int) :
    BaseError(
        HttpStatus.INTERNAL_SERVER_ERROR,
        "Program exited $exitCode",
        mapOf("exitCode" to exitCode),
    )
