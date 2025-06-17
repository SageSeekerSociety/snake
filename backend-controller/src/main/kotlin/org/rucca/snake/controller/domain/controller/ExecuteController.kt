package org.rucca.snake.controller.domain.controller

import jakarta.annotation.PreDestroy
import java.io.IOException
import java.time.Duration
import java.util.*
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.merge
import org.rucca.cheese.auth.AuthenticationService
import org.rucca.cheese.auth.annotation.Guard
import org.rucca.snake.controller.domain.model.ApiError
import org.rucca.snake.controller.domain.model.ApiResponse
import org.rucca.snake.controller.domain.model.BatchExecutionItem
import org.rucca.snake.controller.domain.service.JobFlowService
import org.rucca.snake.controller.domain.service.JobSubmitService
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

@RestController
@RequestMapping("/execute")
class ExecuteController(
    private val jobSubmitService: JobSubmitService,
    private val jobFlowService: JobFlowService,
    private val authenticationService: AuthenticationService,
) {
    private val logger = LoggerFactory.getLogger(ExecuteController::class.java)

    // Timeout for the SSE connection itself (managed by SseEmitter)
    private val sseTimeout = Duration.ofMinutes(5).toMillis()

    // Scope for launching background coroutines related to SSE streaming
    // Using Default dispatcher is fine here, as IO will be handled by services/emitter
    private val controllerScope =
        CoroutineScope(Dispatchers.Default + SupervisorJob() + CoroutineName("CompileController"))

    private fun getCurrentUserId(): Long {
        return authenticationService.getCurrentUserId()
    }

    @Guard("execute", "program")
    @PostMapping("/batch")
    suspend fun submitBatchExecutionRequest(
        @RequestBody requests: List<BatchExecutionItem>
    ): ResponseEntity<ApiResponse> {

        if (requests.isEmpty()) {
            return ResponseEntity.badRequest()
                .body(
                    ApiResponse.Error(
                        code = 400,
                        message = "Execution request batch cannot be empty.",
                        error =
                            ApiError(type = "VALIDATION_ERROR", details = "Request list is empty"),
                    )
                )
        }

        try {
            val currentUserId = getCurrentUserId() // Get current user ID
            val firstSessionId = requests.firstOrNull()?.sessionId

            // Ensure all other items in 'requests' have the SAME sessionId.
            // If they differ, it's a bad request.
            val allEmpty = requests.all { it.sessionId.isNullOrEmpty() }
            val allSameSessionId = requests.all { it.sessionId == firstSessionId }

            if (!allEmpty && !allSameSessionId) {
                return ResponseEntity.badRequest()
                    .body(
                        ApiResponse.Error(
                            code = 400,
                            message = "All batch items must have the same sessionId.",
                            error =
                                ApiError(
                                    type = "VALIDATION_ERROR",
                                    details = "Inconsistent sessionId across batch items.",
                                ),
                        )
                    )
            }

            val finalSessionId = firstSessionId ?: UUID.randomUUID().toString()

            val results: Map<String, Result<String>> =
                jobSubmitService.submitBatchExecution(requests, finalSessionId, currentUserId)

            val responseDataPayload =
                mapOf(
                    "sessionId" to finalSessionId,
                    "jobs" to
                        results.mapValues { (_, result) ->
                            if (result.isSuccess) {
                                mapOf("status" to "SUBMITTED", "jobId" to result.getOrNull())
                            } else {
                                mapOf(
                                    "status" to "ERROR",
                                    "message" to
                                        (result.exceptionOrNull()?.message
                                            ?: "Unknown submission error"),
                                )
                            }
                        },
                )

            logger.info(
                "Batch execution submission processed. SessionId: {}, Results: {}",
                finalSessionId,
                responseDataPayload,
            )
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(
                    ApiResponse.Success(
                        code = 202,
                        message = "Batch execution jobs submitted.",
                        data = responseDataPayload, // This map now contains sessionId
                    )
                )
        } catch (e: Exception) {
            logger.error("Unexpected error during batch execution submission: {}", e.message, e)
            throw e
        }
    }

    @Guard("execute", "program")
    @GetMapping(
        "/stream/{sessionId}",
        produces = [MediaType.TEXT_EVENT_STREAM_VALUE, MediaType.APPLICATION_JSON_VALUE],
    )
    fun streamBatchExecutionEvents(@PathVariable sessionId: String): SseEmitter {
        logger.info("SSE stream connection requested for session ID: {}", sessionId)
        val emitter = SseEmitter(sseTimeout)
        val currentUserId = getCurrentUserId()

        controllerScope.launch {
            var collectingJob: Job? = null
            try {
                val flowsToMerge = jobFlowService.getJobFlowsBySessionId(sessionId, currentUserId)

                if (flowsToMerge.isEmpty()) {
                    logger.warn(
                        "No active jobs found for session {}. Completing SSE stream.",
                        sessionId,
                    )
                    emitter.send(
                        SseEmitter.event()
                            .name("NO_JOBS")
                            .data("No active jobs found for this session.")
                    )
                    emitter.complete()
                    return@launch
                }

                val mergedFlow = merge(*flowsToMerge.toTypedArray())
                var activeJobCount = flowsToMerge.size

                collectingJob =
                    launch(Dispatchers.IO + CoroutineName("SseSessionCollector-$sessionId")) {
                        mergedFlow.collect { event ->
                            try {
                                val originalEventData =
                                    event.data as? Map<*, *> ?: emptyMap<Any, Any>()
                                val clientEventData = originalEventData.toMutableMap()
                                val eventAiUserId = clientEventData["aiUserId"] as? Long
                                val eventSessionId = clientEventData["sessionId"] as? String

                                if (eventAiUserId != currentUserId || eventSessionId != sessionId) {
                                    clientEventData.remove("newMemoryData")
                                }

                                val sseEvent =
                                    SseEmitter.event()
                                        .id(UUID.randomUUID().toString())
                                        .name(event.eventType)
                                        .data(
                                            event.copy(data = clientEventData),
                                            MediaType.APPLICATION_JSON,
                                        )

                                emitter.send(sseEvent)

                                if (
                                    event.eventType == "FINAL_RESULT" || event.eventType == "ERROR"
                                ) {
                                    activeJobCount--
                                    if (activeJobCount <= 0) {
                                        launch {
                                            delay(1.seconds.inWholeMilliseconds)
                                            logger.info(
                                                "Completing batch SSE emitter as all tracked jobs finished for session {}.",
                                                sessionId,
                                            )
                                            emitter.complete()
                                        }
                                    }
                                }
                            } catch (ioe: IOException) {
                                throw CancellationException("Client disconnected", ioe)
                            } catch (e: Exception) {
                                logger.error(
                                    "Error sending batch SSE event for session {}: {}",
                                    sessionId,
                                    e.message,
                                )
                                throw CancellationException("SSE send error", e)
                            }
                        }
                    }
            } catch (e: Exception) {
                logger.error(
                    "Failed to establish SSE stream for session {}: {}",
                    sessionId,
                    e.message,
                    e,
                )
                try {
                    emitter.completeWithError(e)
                } catch (_: Exception) {}
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
        logger.info("Shutting down ExecuteController scope...")
        controllerScope.cancel("Application shutdown")
        // jobFlowService cleanup might be called elsewhere or here too if needed
    }
}
