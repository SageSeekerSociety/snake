package org.rucca.snake.controller

import org.rucca.cheese.auth.annotation.Guard
import org.rucca.snake.controller.api.SubmittersApi
import org.rucca.snake.controller.model.SubmittersGet200ResponseDTO
import org.rucca.snake.controller.model.SubmittersGet200ResponseDataDTO
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController

@RestController
class SubmitterController(private val submitExecService: SubmitExecService) : SubmittersApi {
    @Guard("query", "submitters")
    override fun submittersGet(): ResponseEntity<SubmittersGet200ResponseDTO> {
        val users = submitExecService.getSubmitters()
        val response =
            SubmittersGet200ResponseDTO(
                code = 200,
                message = "Successfully retrieved all submitters",
                data = SubmittersGet200ResponseDataDTO(users = users),
            )
        return ResponseEntity.ok(response)
    }
}
