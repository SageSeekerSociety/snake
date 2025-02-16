package org.rucca.snake.controller

import org.rucca.cheese.auth.annotation.NoAuth
import org.rucca.snake.controller.api.StatusApi
import org.rucca.snake.controller.model.StatusGet200ResponseDTO
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController

@RestController
class StatusController : StatusApi {
    @NoAuth
    override fun statusGet(): ResponseEntity<StatusGet200ResponseDTO> {
        return ResponseEntity.ok(StatusGet200ResponseDTO(code = 200, message = "OK"))
    }
}
