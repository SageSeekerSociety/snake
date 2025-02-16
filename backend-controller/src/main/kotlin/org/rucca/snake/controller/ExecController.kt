package org.rucca.snake.controller

import org.rucca.cheese.auth.annotation.Guard
import org.rucca.snake.controller.api.ExecApi
import org.rucca.snake.controller.model.ExecPost200ResponseDTO
import org.rucca.snake.controller.model.ExecPostRequestDTO
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController

@RestController
class ExecController(private val submitExecService: SubmitExecService) : ExecApi {
    @Guard("execute", "program")
    override fun execPost(
        execPostRequestDTO: ExecPostRequestDTO
    ): ResponseEntity<ExecPost200ResponseDTO> {
        val results = submitExecService.exec(execPostRequestDTO.userIds, execPostRequestDTO.input)
        return ResponseEntity.ok(ExecPost200ResponseDTO(code = 200, message = "OK", data = results))
    }
}
