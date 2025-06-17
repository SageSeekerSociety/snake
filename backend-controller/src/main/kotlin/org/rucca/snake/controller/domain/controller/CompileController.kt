package org.rucca.snake.controller.domain.controller

import jakarta.annotation.PreDestroy
import kotlinx.coroutines.*
import org.rucca.cheese.auth.AuthenticationService
import org.rucca.cheese.auth.annotation.Guard
import org.rucca.snake.controller.domain.model.ApiError
import org.rucca.snake.controller.domain.model.ApiResponse
import org.rucca.snake.controller.domain.service.JobFlowService
import org.rucca.snake.controller.domain.service.JobQueryService
import org.rucca.snake.controller.domain.service.JobSubmitService
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.io.IOException
import java.time.Duration
import java.util.*
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds

@RestController
@RequestMapping("/compile")
class CompileController(
    private val jobSubmitService: JobSubmitService,
    private val jobFlowService: JobFlowService,
    private val authenticationService: AuthenticationService,
    private val jobQueryService: JobQueryService,
) {
    private val logger = LoggerFactory.getLogger(CompileController::class.java)

    // Timeout for the SSE connection itself (managed by SseEmitter)
    private val sseTimeout = Duration.ofMinutes(5).toMillis()

    // Scope for launching background coroutines related to SSE streaming
    // Using Default dispatcher is fine here, as IO will be handled by services/emitter
    private val controllerScope =
        CoroutineScope(Dispatchers.Default + SupervisorJob() + CoroutineName("CompileController"))

    @Guard("submit", "program")
    @PostMapping(consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    suspend fun submitCompileRequest(
        @RequestPart("sourceFile") sourceFile: MultipartFile
    ): ResponseEntity<ApiResponse> {
        val userId = authenticationService.getCurrentUserId()

        if (sourceFile.isEmpty) {
            return ResponseEntity.badRequest()
                .body(
                    ApiResponse.Error(
                        code = 400,
                        message = "Source file cannot be empty.",
                        error = ApiError(type = "VALIDATION_ERROR", details = "sourceFile is empty"),
                    )
                )
        }

        return try {
            sourceFile.inputStream.use { inputStream ->
                val compilationJob =
                    jobSubmitService.submitCompilation(userId, inputStream, sourceFile.size)
                val responseData = mapOf("jobId" to compilationJob.jobId.toString())
                logger.info("Successfully submitted compilation job: {}", compilationJob.jobId)
                ResponseEntity.status(
                    HttpStatus.ACCEPTED
                ) // Use 202 Accepted for async job submission
                    .body(
                        ApiResponse.Success<Any>(
                            code = 202,
                            message = "Compilation job submitted.",
                            data = responseData,
                        )
                    )
            }
        } catch (e: Exception) {
            logger.error(
                "Exception during compilation submission for user {}: {}",
                userId,
                e.message,
            )
            throw e // Re-throw for the handler
        }
    }

    @Guard("submit", "program")
    @GetMapping(
        "/stream/{jobId}",
        produces = [MediaType.TEXT_EVENT_STREAM_VALUE, MediaType.APPLICATION_JSON_VALUE],
    )
    fun streamCompileEvents(@PathVariable jobId: String): SseEmitter {
        val emitter = SseEmitter(sseTimeout)
        val userId = authenticationService.getCurrentUserId()

        logger.info("SSE connection requested for compile job ID: {} by user {}", jobId, userId)

        val jobUUID = runCatching {
            UUID.fromString(jobId)
        }.getOrElse { e ->
            logger.warn("Invalid UUID format for job ID from client: {}", jobId, e)
            emitter.completeWithError(
                IllegalArgumentException("Invalid job ID format: $jobId", e),
            )
            return emitter
        }

        if (!jobQueryService.isOwnerOfCompilationJob(jobUUID, userId)) {
            logger.warn(
                "Unauthorized SSE access attempt for job {} by user {}",
                jobUUID,
                userId,
            )
            emitter.completeWithError(
                IllegalArgumentException("You do not have permission to access this job."),
            )
            return emitter
        }

        controllerScope.launch {
            var collectingJob: Job? = null
            try {
                val jobFlow = jobFlowService.getJobFlow(jobUUID)

                collectingJob =
                    launch(Dispatchers.IO + CoroutineName("SseCollector-$jobUUID")) {
                        jobFlow.collect { event ->
                            try {
                                val sseEvent =
                                    SseEmitter.event()
                                        .id(UUID.randomUUID().toString())
                                        .name(event.eventType)
                                        .data(event, MediaType.APPLICATION_JSON)
                                emitter.send(sseEvent)

                                if (
                                    event.eventType == "FINAL_RESULT" || event.eventType == "ERROR"
                                ) {
                                    launch {
                                        delay(1.seconds.inWholeMilliseconds)
                                        emitter.complete()
                                    }
                                }
                            } catch (ioe: IOException) {
                                throw CancellationException("Client disconnected", ioe)
                            } catch (e: Exception) {
                                logger.error(
                                    "Error sending SSE event for job {}: {}",
                                    jobUUID,
                                    e.message,
                                )
                                throw CancellationException("SSE send error", e)
                            }
                        }
                    }
            } catch (e: Exception) {
                logger.error("Failed to establish SSE stream for job {}: {}", jobUUID, e.message, e)
                try {
                    emitter.completeWithError(e)
                } catch (_: Exception) {
                }
            } finally {
                emitter.onCompletion { collectingJob?.cancel("Emitter completed") }
                emitter.onTimeout { collectingJob?.cancel("Emitter timed out") }
                emitter.onError { err -> collectingJob?.cancel("Emitter error: ${err?.message}") }
            }
        }
        return emitter
    }

    // Cleanup scope on application shutdown
    @PreDestroy
    fun shutdown() {
        logger.info("Shutting down CompileController scope...")
        controllerScope.cancel("Application shutdown")
        // jobFlowService cleanup might be called elsewhere or here too if needed
    }
}
