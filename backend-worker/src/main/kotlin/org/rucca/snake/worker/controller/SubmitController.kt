package org.rucca.snake.worker.controller

import org.rucca.snake.worker.api.SubmitApi
import org.rucca.snake.worker.model.SubmitPost200ResponseDTO
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

@RestController
class SubmitController : SubmitApi {
    override fun submitPost(src: MultipartFile?): ResponseEntity<SubmitPost200ResponseDTO> {
        return super.submitPost(src)
    }
}
