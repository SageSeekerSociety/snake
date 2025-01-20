package org.rucca.snake.controller.controller

import org.rucca.snake.controller.api.ExecApi
import org.rucca.snake.controller.model.ExecPost200ResponseDTO
import org.rucca.snake.controller.model.ExecPostRequestDTO
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
