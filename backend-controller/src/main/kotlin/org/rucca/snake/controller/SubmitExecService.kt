package org.rucca.snake.controller

import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Files
import kotlinx.coroutines.*
import org.rucca.cheese.auth.AuthenticationService
import org.rucca.cheese.auth.persistent.IdType
import org.rucca.snake.controller.error.BypassedError
import org.rucca.snake.controller.model.ExecPost200ResponseDTO
import org.rucca.snake.controller.model.ExecPost200ResponseDataInnerDTO
import org.rucca.snake.controller.model.ExecPostRequestDTO
import org.rucca.snake.controller.model.SubmitPost200ResponseDTO
import org.rucca.snake.controller.model.UserDTO
import org.springframework.core.io.FileSystemResource
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.HttpStatusCodeException
import org.springframework.web.client.RestTemplate
import org.springframework.web.multipart.MultipartFile

@Service
class SubmitExecService(
    private val applicationConfig: ApplicationConfig,
    private val authenticationService: AuthenticationService,
    private val userService: UserService,
    private val submitterRepository: SubmitterRepository,
    private val userRepository: UserRepository,
) {
    private fun getWorkerUrl(userId: IdType): String {
        return applicationConfig.workerUrls[userId.toInt() % applicationConfig.workerUrls.size]
    }

    private val restTemplate = RestTemplate()
    private val objectMapper = ObjectMapper()

    fun submit(userId: IdType, src: MultipartFile): Pair<Boolean, String> {
        val workerUrl = getWorkerUrl(userId)
        val tempFile = Files.createTempFile("upload-", src.originalFilename).toFile()
        src.transferTo(tempFile)
        val fileResource = FileSystemResource(tempFile)
        val httpEntity =
            HttpEntity(
                LinkedMultiValueMap<String, Any>().apply { add("src", fileResource) },
                HttpHeaders()
                    .apply { contentType = MediaType.MULTIPART_FORM_DATA }
                    .apply { set("Authorization", authenticationService.getToken()) },
            )
        return try {
            val response: ResponseEntity<SubmitPost200ResponseDTO> =
                restTemplate.postForEntity(
                    "$workerUrl/submit",
                    httpEntity,
                    SubmitPost200ResponseDTO::class.java,
                )
            if (response.body!!.data.success) upsertSubmitter(userId)
            Pair(response.body!!.data.success, response.body!!.data.diagnose)
        } catch (e: HttpStatusCodeException) {
            throw BypassedError(e.statusCode, objectMapper.readTree(e.responseBodyAsString))
        } finally {
            tempFile.delete()
        }
    }

    fun exec(userIds: List<IdType>, input: String): List<ExecPost200ResponseDataInnerDTO> {
        val userIdsByWorkerUrl = userIds.groupBy { getWorkerUrl(it) }
        val headers =
            HttpHeaders()
                .apply { contentType = MediaType.APPLICATION_JSON }
                .apply { set("Authorization", authenticationService.getToken()) }
        return runBlocking(Dispatchers.IO) {
            val deferredResults =
                userIdsByWorkerUrl.map { (workerUrl, userIds) ->
                    async {
                        val httpEntity =
                            HttpEntity(
                                ExecPostRequestDTO(userIds = userIds, input = input),
                                headers,
                            )
                        try {
                            val response: ResponseEntity<ExecPost200ResponseDTO> =
                                restTemplate.postForEntity(
                                    "$workerUrl/exec",
                                    httpEntity,
                                    ExecPost200ResponseDTO::class.java,
                                )
                            response.body!!.data
                        } catch (e: HttpStatusCodeException) {
                            throw BypassedError(
                                e.statusCode,
                                objectMapper.readTree(e.responseBodyAsString),
                            )
                        }
                    }
                }
            deferredResults.awaitAll().flatten()
        }
    }

    private fun upsertSubmitter(userId: IdType) {
        val submitter = submitterRepository.findById(userId)
        if (submitter.isEmpty) {
            submitterRepository.save(
                Submitter(user = userRepository.getReferenceById(userId.toInt()))
            )
        }
    }

    fun getSubmitters(): List<UserDTO> {
        val submitterIds = submitterRepository.findAll().map { it.user!! }
        return userService.convertUsersToDto(submitterIds).values.toList()
    }
}
