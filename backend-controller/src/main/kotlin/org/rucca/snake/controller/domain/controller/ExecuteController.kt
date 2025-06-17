package org.rucca.snake.controller.domain.controller

import jakarta.annotation.PreDestroy
import java.io.IOException
import java.time.Duration
import java.util.*
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.merge
import org.rucca.cheese.auth.annotation.Guard
import org.rucca.snake.controller.domain.model.ApiError
import org.rucca.snake.controller.domain.model.ApiResponse
import org.rucca.snake.controller.domain.model.BatchExecutionItem
import org.rucca.snake.controller.domain.model.JobSseEvent
import org.rucca.snake.controller.domain.service.JobFlowService
import org.rucca.snake.controller.domain.service.JobSubmitService
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

@RestController
@RequestMapping("/execute")
class ExecuteController(
    private val jobSubmitService: JobSubmitService,
    private val jobFlowService: JobFlowService,
) {
    private val logger = LoggerFactory.getLogger(ExecuteController::class.java)

    // Timeout for the SSE connection itself (managed by SseEmitter)
    private val sseTimeout = Duration.ofMinutes(5).toMillis()

    // Scope for launching background coroutines related to SSE streaming
    // Using Default dispatcher is fine here, as IO will be handled by services/emitter
    private val controllerScope =
        CoroutineScope(Dispatchers.Default + SupervisorJob() + CoroutineName("CompileController"))

    // Placeholder for getting current user ID
    private fun getCurrentUserId(): Long {
        // In a real application, this would be retrieved from Spring Security Context
        // For example:
        // val authentication = SecurityContextHolder.getContext().authentication
        // if (authentication != null && authentication.principal is CustomUserDetails) {
        //     return (authentication.principal as CustomUserDetails).id
        // }
        // return -1L // Or throw an exception if user not found
        return 123L // Placeholder
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
            val finalSessionId =
                requests.firstOrNull()?.sessionId ?: UUID.randomUUID().toString()

            // TODO: Add validation here: if requests.firstOrNull()?.sessionId is not null,
            // ensure all other items in 'requests' have the SAME sessionId.
            // If they differ, it's a bad request. For now, we trust the first or generate new.

            val results: Map<String, Result<String>> =
                jobSubmitService.submitBatchExecution(requests, finalSessionId, currentUserId)

            val responseDataPayload = mutableMapOf<String, Any>("sessionId" to finalSessionId)
            results.forEach { (clientReqId, result) ->
                responseDataPayload[clientReqId] =
                    if (result.isSuccess) {
                        mapOf("status" to "SUBMITTED", "jobId" to result.getOrNull())
                    } else {
                        mapOf(
                            "status" to "ERROR",
                            "message" to
                                (result.exceptionOrNull()?.message ?: "Unknown submission error"),
                        )
                    }
            }

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
    @PostMapping("/batch/stream")
    fun submitBatchExecutionAndStreamEvents(
        @RequestBody requests: List<BatchExecutionItem>
    ): ResponseEntity<SseEmitter> {
        logger.info("SSE batch execute request received for {} items", requests.size)

        if (requests.isEmpty()) {
            // Handle empty batch validation synchronously before creating emitter
            // This part needs a regular error response, not SseEmitter
            val errorResponse =
                ApiResponse.Error(
                    code = 4002,
                    message = "Execution request batch cannot be empty.",
                    error = ApiError(type = "VALIDATION_ERROR", details = "Request list is empty"),
                )
            // How to return this instead of SseEmitter? This highlights a design challenge.
            // Option A: Throw exception handled by GlobalExceptionHandler (cleaner)
            // Option B: Return ResponseEntity<ApiResponse> and handle emitter separately (more
            // complex)
            // Let's assume validation exception is thrown and caught by handler for non-SSE return.
            // If validation needs to happen *before* returning emitter, do it here.
            // For now, proceed and handle errors async.
            logger.warn("Received empty batch execution request for SSE.")
        }

        val emitter = SseEmitter(sseTimeout)
        val currentUserId = getCurrentUserId() // Get current user ID
        val finalSessionId = requests.firstOrNull()?.sessionId ?: UUID.randomUUID().toString()

        // TODO: Add validation for session ID consistency in batch (similar to non-SSE endpoint)

        controllerScope.launch {
            var collectingJob: Job? = null
            val submittedJobIds = mutableMapOf<String, UUID>() // clientReqId -> JobUUID
            val failedSubmissions = mutableMapOf<String, String>() // clientReqId -> Error Message

            try {
                // Send SESSION_INIT event
                try {
                    emitter.send(
                        SseEmitter.event()
                            .name("SESSION_INIT")
                            .id(UUID.randomUUID().toString())
                            .data(mapOf("sessionId" to finalSessionId), MediaType.APPLICATION_JSON)
                    )
                    logger.info("Sent SESSION_INIT event with sessionId: {}", finalSessionId)
                } catch (e: Exception) {
                    logger.warn(
                        "Failed to send SESSION_INIT event for session {}: {}",
                        finalSessionId,
                        e.message,
                    )
                    // Depending on requirements, might want to complete emitter with error here
                }

                // 1. Submit Batch (this is suspend)
                val submissionResults =
                    jobSubmitService.submitBatchExecution(requests, finalSessionId, currentUserId)

                // 2. Process submission results and prepare flows
                val flowsToMerge = mutableListOf<SharedFlow<JobSseEvent>>()
                requests.forEachIndexed { index, item ->
                    val resultKey =
                        item.clientRequestId
                            ?: "batch_item_${index}_[unknown]" // Use client ID or temp
                    val result = submissionResults[resultKey]

                    val eventToSend: JobSseEvent
                    if (result != null && result.isSuccess) {
                        val jobIdStr = result.getOrThrow()
                        val jobUUID = UUID.fromString(jobIdStr)
                        submittedJobIds[resultKey] = jobUUID // Track successful ones
                        flowsToMerge.add(jobFlowService.getJobFlow(jobUUID)) // Add flow for merging
                        eventToSend =
                            JobSseEvent(
                                jobIdStr,
                                "SUBMITTED",
                                message = "Job submitted successfully.",
                                data = mapOf("clientRequestId" to resultKey),
                            )
                        logger.info(
                            "Batch item submitted successfully: ClientReqId={}, JobId={}",
                            resultKey,
                            jobIdStr,
                        )
                    } else {
                        val errorMsg =
                            result?.exceptionOrNull()?.message ?: "Unknown submission error"
                        failedSubmissions[resultKey] = errorMsg
                        eventToSend =
                            JobSseEvent(
                                resultKey,
                                "ERROR",
                                message = "Job submission failed: $errorMsg",
                                data = mapOf("clientRequestId" to resultKey),
                            )
                        logger.error(
                            "Batch item submission failed: ClientReqId={}, Error: {}",
                            resultKey,
                            errorMsg,
                        )
                    }
                    // Send immediate feedback for each item's submission status
                    try {
                        emitter.send(
                            SseEmitter.event()
                                .name(eventToSend.eventType)
                                .data(eventToSend, MediaType.APPLICATION_JSON)
                        )
                    } catch (e: Exception) {
                        logger.warn(
                            "Failed to send initial submission status event for {}: {}",
                            resultKey,
                            e.message,
                        )
                        // If emitter fails here early, subsequent logic might fail too
                        throw CancellationException("Emitter failed during initial status send")
                    }
                }

                if (flowsToMerge.isEmpty()) {
                    logger.info("No jobs submitted successfully in batch SSE request.")
                    try {
                        emitter.complete()
                    } catch (_: Exception) {}
                    return@launch // Exit coroutine if no jobs to track
                }

                // 3. Merge flows
                val mergedFlow = merge(*flowsToMerge.toTypedArray())

                // 4. Collect merged flow and send events
                var activeJobCount =
                    flowsToMerge.size // Track remaining jobs from the successful submissions
                collectingJob =
                    launch(Dispatchers.IO + CoroutineName("SseBatchCollector")) {
                        try {
                            mergedFlow.collect { event ->
                                try {
                                    // currentUserId and finalSessionId are from the outer scope
                                    val originalEventData = event.data as? Map<*, *> ?: emptyMap<Any, Any>()
                                    val clientEventData = originalEventData.toMutableMap()

                                    val eventAiUserId = clientEventData["aiUserId"] as? Long
                                    val eventSessionId = clientEventData["sessionId"] as? String

                                    if (eventAiUserId == currentUserId && eventSessionId == finalSessionId) {
                                        // This event is for the current user and current session, keep newMemoryData
                                        logger.debug(
                                            "Memory data for job {} (user {}, session {}) will be sent to current SSE user {} (SSE session: {})",
                                            event.jobId,
                                            eventAiUserId,
                                            eventSessionId,
                                            currentUserId,
                                            finalSessionId,
                                        )
                                    } else {
                                        // Not for the current user/session, or types don't match, remove memory data
                                        clientEventData.remove("newMemoryData")
                                        logger.debug(
                                            "Memory data for job {} (user {}, session {}) will NOT be sent to current SSE user {} (SSE session: {}). Reason: User/Session mismatch.",
                                            event.jobId,
                                            eventAiUserId,
                                            eventSessionId,
                                            currentUserId,
                                            finalSessionId,
                                        )
                                    }

                                    val sseEventBuilder = SseEmitter.event()
                                        .id(UUID.randomUUID().toString()) // Consider using event.id if available and unique
                                        .name(event.eventType)
                                        // Send the potentially modified clientEventData
                                        // If JobSseEvent structure is { jobId, eventType, status, message, data: { actual_payload } }
                                        // then we should reconstruct the top-level JobSseEvent for clarity or send clientEventData directly
                                        // Assuming the listener wants the "data" part of JobSseEvent directly:
                                        .data(clientEventData, MediaType.APPLICATION_JSON)
                                        // If the listener expects the full JobSseEvent structure, but with modified data:
                                        // .data(event.copy(data = clientEventData), MediaType.APPLICATION_JSON)
                                        // For now, sending clientEventData directly as per prompt's implication.

                                    emitter.send(sseEventBuilder)
                                    logger.trace(
                                        "Sent batch SSE event: {} for job {}",
                                        event.eventType,
                                        event.jobId,
                                    )

                                    if (
                                        event.eventType == "FINAL_RESULT" ||
                                            event.eventType == "ERROR"
                                    ) {
                                        activeJobCount--
                                        logger.debug(
                                            "Job {} finished in batch, {} remaining.",
                                            event.jobId,
                                            activeJobCount,
                                        )
                                        if (activeJobCount <= 0) {
                                            launch {
                                                delay(1.seconds.inWholeMilliseconds)
                                                logger.info(
                                                    "Completing batch SSE emitter as all tracked jobs finished."
                                                )
                                                try {
                                                    emitter.complete()
                                                } catch (_: Exception) {}
                                            }
                                        }
                                    }
                                } catch (ioe: IOException) {
                                    throw CancellationException("Client disconnected", ioe)
                                } catch (e: Exception) {
                                    logger.error("Error sending batch SSE event: {}", e.message)
                                    throw CancellationException("SSE send error", e)
                                }
                            }
                            // Merged flow completed
                            logger.info("Merged flow collection completed for batch.")
                            try {
                                if (activeJobCount > 0)
                                    logger.warn(
                                        "Merged flow done, but {} jobs potentially incomplete?",
                                        activeJobCount,
                                    )
                                emitter.complete()
                            } catch (_: Exception) {}
                        } catch (ce: CancellationException) {
                            logger.info("Batch SSE collection cancelled: {}", ce.message)
                        } catch (e: Exception) {
                            logger.error("Error collecting merged flow: {}", e.message, e)
                            try {
                                emitter.completeWithError(e)
                            } catch (_: Exception) {}
                        } finally {
                            logger.debug("Batch collector coroutine finished.")
                        }
                    } // End collectingJob launch

                // Register emitter callbacks *after* starting collector launch
                emitter.onCompletion {
                    logger.info("Batch SSE emitter completed.")
                    collectingJob.cancel("Emitter completed")
                }
                emitter.onTimeout {
                    logger.warn("Batch SSE emitter timed out.")
                    collectingJob.cancel("Emitter timed out")
                }
                emitter.onError { err ->
                    logger.error("Batch SSE emitter error: {}", err?.message)
                    collectingJob.cancel("Emitter error")
                }
            } catch (e: Exception) {
                // Handle errors during the *initial batch submission* phase
                logger.error("Failed to initiate SSE stream for batch request: {}", e.message, e)
                val errorEvent =
                    JobSseEvent(
                        jobId = "BATCH_SUBMISSION_ERROR",
                        eventType = "ERROR",
                        message = "Batch job submission failed: ${e.message}",
                    )
                try {
                    emitter.send(
                        SseEmitter.event()
                            .name("ERROR")
                            .data(errorEvent, MediaType.APPLICATION_JSON)
                    )
                    emitter.completeWithError(e)
                } catch (_: Exception) {
                    try {
                        emitter.completeWithError(e)
                    } catch (_: Exception) {}
                }
            }
        } // End controllerScope.launch

        logger.debug("Returning SseEmitter for batch request immediately.")
        return ResponseEntity.ok(emitter) // Return emitter wrapped in ResponseEntity
    }

    // Cleanup scope on application shutdown
    @PreDestroy
    fun shutdown() {
        logger.info("Shutting down ExecuteController scope...")
        controllerScope.cancel("Application shutdown")
        // jobFlowService cleanup might be called elsewhere or here too if needed
    }
}
