package org.rucca.snake.controller

import org.rucca.cheese.auth.AuthenticationService
import org.rucca.cheese.auth.annotation.Guard
import org.rucca.snake.controller.api.SubmitApi
import org.rucca.snake.controller.model.SubmitPost200ResponseDTO
import org.rucca.snake.controller.model.SubmitPost200ResponseDataDTO
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

@RestController
class SubmitController(
    private val submitService: SubmitExecService,
    private val authenticationService: AuthenticationService,
) : SubmitApi {
    @Guard("submit", "program")
    override fun submitPost(src: MultipartFile): ResponseEntity<SubmitPost200ResponseDTO> {
        val userId = authenticationService.getCurrentUserId()
        val (success, diagnose) = submitService.submit(userId, src)
        return ResponseEntity.ok(
            SubmitPost200ResponseDTO(
                code = 200,
                message = "OK",
                SubmitPost200ResponseDataDTO(success = success, diagnose = diagnose),
            )
        )
    }
}
