package org.rucca.snake.controller.domain.controller

import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.context.Context
import io.opentelemetry.extension.kotlin.asContextElement
import jakarta.annotation.PreDestroy
import java.io.IOException
import java.time.Duration
import java.util.*
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filter
import org.apache.catalina.connector.ClientAbortException
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
    openTelemetry: OpenTelemetry,
) {
    private val logger = LoggerFactory.getLogger(ExecuteController::class.java)
    private val tracer = openTelemetry.getTracer(ExecuteController::class.java.name)

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
            val finalRequests = requests.map { it.copy(sessionId = finalSessionId) }

            val results: Map<String, Result<String>> =
                jobSubmitService.submitBatchExecution(finalRequests, finalSessionId, currentUserId)

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
    fun streamBatchExecutionEvents(
        @PathVariable sessionId: String,
        @RequestParam(required = false, defaultValue = "0") fromTick: Int,
    ): SseEmitter {
        logger.info("SSE stream connection requested for session ID: {}", sessionId)
        val emitter = SseEmitter(sseTimeout)
        var collectingJob: Job? = null

        emitter.onCompletion { collectingJob?.cancel("Emitter completed") }
        emitter.onTimeout { collectingJob?.cancel("Emitter timed out") }
        emitter.onError { err -> collectingJob?.cancel("Emitter error: ${err?.message}") }

        if (fromTick < 0) {
            logger.warn("Invalid 'fromTick' value: {}. Must be non-negative.", fromTick)
            emitter.completeWithError(
                IllegalArgumentException("Invalid 'fromTick' value: $fromTick")
            )
            return emitter
        }

        val currentUserId = getCurrentUserId()

        val sessionIdAsUUID =
            runCatching { UUID.fromString(sessionId) }
                .getOrElse {
                    logger.warn("Invalid UUID format for session ID from client: {}", sessionId)
                    emitter.completeWithError(it)
                    return emitter
                }

        // Launch the main coroutine to manage the stream's lifecycle.
        val reqCtx = Context.current()
        controllerScope.launch(reqCtx.asContextElement()) {
            val streamSpan =
                tracer
                    .spanBuilder("sse.stream")
                    .setSpanKind(SpanKind.INTERNAL)
                    .setParent(reqCtx)
                    .startSpan()

            try {
                streamSpan.makeCurrent().use {
                    // Launch a heartbeat coroutine to keep the connection alive through proxies.
                    val heartbeatJob = launch {
                        while (isActive) {
                            delay(25.seconds)
                            emitter.send(SseEmitter.event().comment("heartbeat"))
                        }
                    }

                    // Launch the primary job to collect and send events.
                    collectingJob =
                        launch(Dispatchers.IO + CoroutineName("SseSessionCollector-$sessionId")) {
                            // 1. Get the single, session-level flow. This will emit events for all
                            //    jobs associated with this session, both past and future.
                            val sessionFlow = jobFlowService.getSessionFlow(sessionIdAsUUID)

                            // 2. Filter the stream based on the 'fromTick' parameter and collect
                            // events.
                            sessionFlow
                                .filter { event ->
                                    val eventData = event.data as? Map<*, *>
                                    val eventTick = eventData?.get("tickNumber") as? Number
                                    // Pass through events without a tick (e.g., global errors) or
                                    // events >= fromTick.
                                    eventTick == null || eventTick.toLong() >= fromTick
                                }
                                .collect { event ->
                                    try {
                                        // 3. Filter sensitive data before sending.
                                        val originalEventData =
                                            event.data as? Map<*, *> ?: emptyMap<Any, Any>()
                                        val clientEventData = originalEventData.toMutableMap()

                                        val eventAiUserId =
                                            clientEventData["userId"]
                                                as? Long // Assuming key is 'userId' in final DTO
                                        if (eventAiUserId != currentUserId) {
                                            clientEventData.remove("newMemoryData")
                                        }

                                        // 4. Construct and send the SSE event.
                                        val sseEvent =
                                            SseEmitter.event()
                                                .id(UUID.randomUUID().toString())
                                                .name(event.eventType)
                                                .data(
                                                    event.copy(data = clientEventData),
                                                    MediaType.APPLICATION_JSON,
                                                )

                                        emitter.send(sseEvent)
                                    } catch (ioe: IOException) {
                                        throw CancellationException("Client disconnected.", ioe)
                                    } catch (e: Exception) {
                                        logger.error(
                                            "Error sending event for session {}: {}",
                                            sessionId,
                                            e.message,
                                        )
                                        // Cancel the collector job on send error. The emitter's
                                        // onError
                                        // will be triggered.
                                        throw CancellationException("Failed to send SSE event.", e)
                                    }
                                }
                        }

                    // Suspend the main coroutine until its children (heartbeat, collector) are
                    // cancelled.
                    listOfNotNull(heartbeatJob, collectingJob).joinAll()

                    // If we reach here, it means the stream was completed successfully.
                    emitter.complete()
                }
            } catch (e: CancellationException) {
                logger.info(
                    "SSE Stream coroutine for session {} was cancelled: {}",
                    sessionId,
                    e.message,
                )
            } catch (e: Exception) {
                val rootCause = e.cause ?: e
                if (rootCause is ClientAbortException || rootCause is IOException) {
                    logger.warn("Client disconnected for session {}: {}", sessionId, e.message)
                } else {
                    logger.error("Critical error in SSE stream for session ${sessionId}.", e)

                    try {
                        emitter.completeWithError(e)
                    } catch (t: Throwable) {
                        logger.warn(
                            "Failed to send final error to SSE stream for session {}, connection likely closed. Details: {}",
                            sessionId,
                            t.message,
                        )
                    }
                }
            } finally {
                streamSpan.end()
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
