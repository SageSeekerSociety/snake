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
            val results: Map<String, Result<String>> =
                jobSubmitService.submitBatchExecution(requests)
            val responseData =
                results.mapValues { (_, result) ->
                    if (result.isSuccess)
                        mapOf("status" to "SUBMITTED", "jobId" to result.getOrNull())
                    else
                        mapOf(
                            "status" to "ERROR",
                            "message" to
                                (result.exceptionOrNull()?.message ?: "Unknown submission error"),
                        )
                }
            logger.info("Batch execution submission processed. Results: {}", responseData)
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(
                    ApiResponse.Success(
                        code = 202,
                        message = "Batch execution jobs submitted.",
                        data = responseData,
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

        controllerScope.launch {
            var collectingJob: Job? = null
            val submittedJobIds = mutableMapOf<String, UUID>() // clientReqId -> JobUUID
            val failedSubmissions = mutableMapOf<String, String>() // clientReqId -> Error Message

            try {
                // 1. Submit Batch (this is suspend)
                val submissionResults = jobSubmitService.submitBatchExecution(requests)

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
                                    val sseEvent =
                                        SseEmitter.event()
                                            .id(UUID.randomUUID().toString())
                                            .name(event.eventType)
                                            .data(event, MediaType.APPLICATION_JSON)
                                    emitter.send(sseEvent)
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
