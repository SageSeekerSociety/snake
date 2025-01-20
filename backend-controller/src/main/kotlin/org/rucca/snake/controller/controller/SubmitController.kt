package org.rucca.snake.controller.controller

import org.rucca.snake.controller.api.SubmitApi
import org.rucca.snake.controller.model.SubmitPost200ResponseDTO
import org.springframework.core.io.Resource
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController

@RestController
class SubmitController : SubmitApi {
    override fun submitPost(src: Resource?): ResponseEntity<SubmitPost200ResponseDTO> {
        return super.submitPost(src)
    }
}
