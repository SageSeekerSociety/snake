package org.rucca.snake.controller.domain.controller

import jakarta.annotation.PreDestroy
import java.io.IOException
import java.time.Duration
import java.util.*
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import org.rucca.cheese.auth.AuthenticationService
import org.rucca.cheese.auth.annotation.Guard
import org.rucca.snake.controller.domain.model.ApiError
import org.rucca.snake.controller.domain.model.ApiResponse
import org.rucca.snake.controller.domain.model.JobSseEvent
import org.rucca.snake.controller.domain.service.JobFlowService
import org.rucca.snake.controller.domain.service.JobSubmitService
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

@RestController
@RequestMapping("/compile")
class CompileController(
    private val jobSubmitService: JobSubmitService,
    private val jobFlowService: JobFlowService,
    private val authenticationService: AuthenticationService,
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
    @PostMapping("/stream", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun submitCompileAndStreamEvents(
        @RequestPart("sourceFile") sourceFile: MultipartFile
    ): ResponseEntity<SseEmitter> {
        val userId = authenticationService.getCurrentUserId()

        logger.info("SSE compile request received for user {}", userId)

        // Basic validation before starting anything
        if (sourceFile.isEmpty) {
            // Cannot return SseEmitter for a bad request before processing starts
            // This scenario needs careful handling. Maybe return an immediate error response?
            // For simplicity here, we might proceed and let the async block handle it,
            // but ideally validation happens synchronously first.
            // Let's assume basic validation passes for now, or is handled by exception advice.
            logger.warn("Received empty source file for user {}", userId)
            // Returning error immediately might be better:
            // return ResponseEntity.badRequest().body(/* ErrorResponse DTO or similar */)
        }

        val emitter = SseEmitter(sseTimeout)
        var submissionJobId: UUID? = null // Variable to hold the jobId after submission

        // Launch an asynchronous coroutine to handle submission and streaming
        // This coroutine runs independently of the controller method returning
        controllerScope.launch {
            var collectingJob: Job? = null // Job for the flow collection coroutine

            try {
                // 1. Submit the job within the coroutine
                // Use try-with-resources for the input stream
                val job =
                    try {
                        sourceFile.inputStream.use { inputStream ->
                            jobSubmitService.submitCompilation(userId, inputStream, sourceFile.size)
                        }
                    } catch (e: Exception) {
                        logger.error(
                            "Failed during job submission phase for SSE request (User {}): {}",
                            userId,
                            e.message,
                            e,
                        )
                        throw e // Propagate submission error
                    }
                submissionJobId = job.jobId // Store the successfully obtained jobId
                logger.info("Compile job {} submitted, beginning SSE stream.", submissionJobId)

                // 2. Get the flow (JobFlowService already ensures it exists and publishes
                // SUBMITTED)
                val jobFlow = jobFlowService.getJobFlow(submissionJobId!!)

                // 3. Launch a nested coroutine to collect events and send via emitter
                collectingJob =
                    launch(
                        Dispatchers.IO + CoroutineName("SseCollector-${submissionJobId}")
                    ) { // Use IO dispatcher for emitter.send()
                        try {
                            jobFlow.collect { event ->
                                try {
                                    val sseEvent =
                                        SseEmitter.event()
                                            .id(UUID.randomUUID().toString())
                                            .name(event.eventType)
                                            .data(event, MediaType.APPLICATION_JSON)
                                    emitter.send(sseEvent)
                                    logger.trace(
                                        "Sent SSE event: {} for job {}",
                                        event.eventType,
                                        submissionJobId,
                                    )

                                    if (
                                        event.eventType == "FINAL_RESULT" ||
                                            event.eventType == "ERROR"
                                    ) {
                                        launch { // Non-blocking delay for completion
                                            delay(1.seconds.inWholeMilliseconds)
                                            logger.info(
                                                "Completing SSE emitter for job {} after final event.",
                                                submissionJobId,
                                            )
                                            try {
                                                emitter.complete()
                                            } catch (_: Exception) {} // Ignore errors on complete
                                        }
                                    }
                                } catch (ioe: IOException) {
                                    // Client disconnected
                                    logger.warn(
                                        "IOException sending SSE for job {}: {} (Client likely disconnected)",
                                        submissionJobId,
                                        ioe.message,
                                    )
                                    throw CancellationException("Client disconnected", ioe)
                                } catch (e: Exception) {
                                    // Other send errors
                                    logger.error(
                                        "Error sending SSE event for job {}: {}",
                                        submissionJobId,
                                        e.message,
                                        e,
                                    )
                                    try {
                                        emitter.completeWithError(e)
                                    } catch (_: Exception) {}
                                    throw CancellationException("SSE send error", e)
                                }
                            }
                            // Flow completed normally
                            logger.info(
                                "Job flow collection completed normally for job {}.",
                                submissionJobId,
                            )
                            try {
                                emitter.complete()
                            } catch (_: Exception) {} // Best effort complete
                        } catch (ce: CancellationException) {
                            logger.info(
                                "SSE collection for job {} cancelled: {}",
                                submissionJobId,
                                ce.message,
                            )
                            // Don't complete emitter here, might be handled by emitter callbacks
                        } catch (e: Exception) {
                            logger.error(
                                "Error collecting job flow for job {}: {}",
                                submissionJobId,
                                e.message,
                                e,
                            )
                            try {
                                emitter.completeWithError(e)
                            } catch (_: Exception) {}
                        } finally {
                            logger.debug("Collector coroutine finished for job {}", submissionJobId)
                        }
                    } // End of collectingJob launch

                // Register callbacks on the emitter *after* starting the collector launch
                emitter.onCompletion {
                    logger.info("SSE emitter completed for job {}", submissionJobId ?: "[unknown]")
                    collectingJob.cancel(
                        "Emitter completed"
                    ) // Cancel collection if emitter completes
                }
                emitter.onTimeout {
                    logger.warn("SSE emitter timed out for job {}", submissionJobId ?: "[unknown]")
                    collectingJob.cancel("Emitter timed out") // Cancel collection on timeout
                }
                emitter.onError { error ->
                    logger.error(
                        "SSE emitter error for job {}: {}",
                        submissionJobId ?: "[unknown]",
                        error?.message,
                    )
                    collectingJob.cancel("Emitter error") // Cancel collection on error
                }
            } catch (e: Exception) {
                // Handle errors during the *initial submission* phase (before streaming starts)
                logger.error(
                    "Failed to initiate SSE stream for compile request (User {}): {}",
                    userId,
                    e.message,
                )
                val errorEvent =
                    JobSseEvent(
                        jobId = submissionJobId?.toString() ?: "SUBMISSION_FAILED",
                        eventType = "ERROR",
                        message = "Job submission failed: ${e.message}",
                    )
                try {
                    // Try sending one error event before completing
                    emitter.send(
                        SseEmitter.event()
                            .name("ERROR")
                            .data(errorEvent, MediaType.APPLICATION_JSON)
                    )
                    emitter.completeWithError(e)
                } catch (_: Exception) {
                    // If sending the error itself fails, just ensure completion
                    try {
                        emitter.completeWithError(e)
                    } catch (_: Exception) {}
                }
            }
        } // End controllerScope.launch

        // Return the emitter immediately to Spring MVC
        logger.debug("Returning SseEmitter to container immediately.")
        // It's better practice to return the SseEmitter directly, not wrapped in ResponseEntity
        // Spring handles the setup. However, returning ResponseEntity allows setting headers etc.
        // if needed.
        // Let's return ResponseEntity for consistency with potential error returns during
        // validation.
        return ResponseEntity.ok()
            // Add headers if needed, e.g., cache control
            // .header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_EVENT_STREAM_VALUE)
            // .header(HttpHeaders.CACHE_CONTROL, "no-cache")
            .body(emitter)
    }

    // Cleanup scope on application shutdown
    @PreDestroy
    fun shutdown() {
        logger.info("Shutting down CompileController scope...")
        controllerScope.cancel("Application shutdown")
        // jobFlowService cleanup might be called elsewhere or here too if needed
    }
}
