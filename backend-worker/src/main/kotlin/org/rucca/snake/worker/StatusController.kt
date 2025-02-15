package org.rucca.snake.worker

import org.rucca.cheese.auth.annotation.NoAuth
import org.rucca.snake.worker.api.StatusApi
import org.rucca.snake.worker.model.StatusGet200ResponseDTO
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController

@RestController
class StatusController : StatusApi {
    @NoAuth
    override fun statusGet(): ResponseEntity<StatusGet200ResponseDTO> {
        return ResponseEntity.ok(StatusGet200ResponseDTO(code = 200, message = "OK"))
    }
}
