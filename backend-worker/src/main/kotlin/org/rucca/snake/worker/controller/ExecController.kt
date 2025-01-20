package org.rucca.snake.worker.controller

import org.rucca.snake.worker.api.ExecApi
import org.rucca.snake.worker.model.ExecPost200ResponseDTO
import org.rucca.snake.worker.model.ExecPostRequestDTO
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController

@RestController
class ExecController : ExecApi {
    override fun execPost(
        execPostRequestDTO: ExecPostRequestDTO
    ): ResponseEntity<ExecPost200ResponseDTO> {
        return super.execPost(execPostRequestDTO)
    }
}
