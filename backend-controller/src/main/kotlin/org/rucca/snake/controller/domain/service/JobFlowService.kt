package org.rucca.snake.controller.domain.service

import jakarta.annotation.PreDestroy
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import org.rucca.snake.controller.domain.model.JobSseEvent
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class JobFlowService(
    // Inject JobQueryService to potentially fetch initial/final state if needed
    private val jobQueryService: JobQueryService
) {
    private val logger = LoggerFactory.getLogger(JobFlowService::class.java)

    // Scope for managing flows and cleanup tasks
    private val scope =
        CoroutineScope(Dispatchers.Default + SupervisorJob() + CoroutineName("JobFlowScope"))

    // Map to store active MutableSharedFlows for each Job ID
    // SharedFlow allows multiple SSE clients to subscribe to the same job updates (if needed)
    // Use BufferOverflow.DROP_OLDEST or similar strategy if backpressure is a concern
    private val jobFlows = ConcurrentHashMap<UUID, MutableSharedFlow<JobSseEvent>>()

    private val sessionFlows = ConcurrentHashMap<UUID, MutableSharedFlow<JobSseEvent>>()

    // Timeout for inactive flows (e.g., 5 minutes after last update/access)
    private val flowTimeout = 5.minutes

    /**
     * Returns a SharedFlow for the specified job ID, creating it if it does not already exist.
     *
     * Ensures that only one flow exists per job. The flow replays all past events to new
     * subscribers and is suitable for use with Server-Sent Events (SSE) clients.
     *
     * @param jobId The unique identifier of the job.
     * @return A SharedFlow emitting job events for the given job ID.
     */
    fun getJobFlow(jobId: UUID): SharedFlow<JobSseEvent> {
        // Compute if absent to ensure only one flow per jobId
        return jobFlows.computeIfAbsent(jobId) { newJobId ->
            logger.info("Creating new SharedFlow for jobId: {}", newJobId)
            val newFlow =
                MutableSharedFlow<JobSseEvent>(
                    replay = Int.MAX_VALUE,
                    extraBufferCapacity = 16,
                    onBufferOverflow = BufferOverflow.DROP_OLDEST,
                )

            // Launch a cleanup task for this specific flow when it's created
            launchFlowCleanup(newJobId, newFlow, "Job")
            newFlow
        }
    }

    /**
     * Gets or creates a session-level SharedFlow. This flow aggregates all events for a given
     * session. This is the primary method the SSE controller should use.
     */
    fun getSessionFlow(sessionId: UUID): SharedFlow<JobSseEvent> {
        return sessionFlows.computeIfAbsent(sessionId) { newSessionId ->
            logger.info("Creating new Session-level SharedFlow for sessionId: {}", newSessionId)
            MutableSharedFlow<JobSseEvent>(
                    replay = Int.MAX_VALUE, // Also replay for late-joining spectators
                    extraBufferCapacity = 64, // Larger buffer for session
                    onBufferOverflow = BufferOverflow.DROP_OLDEST,
                )
                .also { launchFlowCleanup(newSessionId, it, "Session") }
        }
    }

    /**
     * Retrieves all job event flows associated with a given session and requesting user.
     *
     * @param sessionId The session identifier to query jobs for.
     * @param requestingUserId The user requesting the job flows.
     * @return A list of shared flows, each emitting events for a specific job in the session.
     */
    fun getJobFlowsBySessionId(
        sessionId: UUID,
        requestingUserId: Long,
        fromTick: Int = 0,
    ): List<Flow<JobSseEvent>> {
        return jobQueryService.getJobIdsBySessionId(sessionId, requestingUserId).distinct().map {
            jobId ->
            getJobFlow(jobId).filter { event ->
                val eventData = event.data as? Map<*, *>
                val eventTick = eventData?.get("tickNumber") as? Number

                // If eventTick is null, allow all events (e.g., global errors)
                // Otherwise, filter by tickNumber
                eventTick == null || eventTick.toLong() >= fromTick
            }
        }
    }

    /**
     * Publishes a new event to the flow associated with the jobId. Called by ResultListener or
     * potentially JobSubmitService for initial status.
     */
    suspend fun publishEvent(jobId: UUID, event: JobSseEvent) {
        // 1. Emit to the individual job's flow (for potential direct job listeners)
        jobFlows[jobId]?.emit(event)
            ?: logger.warn("Job flow for {} not found, cannot publish event.", jobId)

        // 2. If the event has a sessionId, also emit it to the session-level flow.
        event.sessionId?.let { sidString ->
            try {
                val sessionId = UUID.fromString(sidString)
                // Ensure the session flow exists, then emit.
                getSessionFlow(sessionId).let { sessionFlow ->
                    (sessionFlow as? MutableSharedFlow)?.emit(event)
                }
            } catch (e: IllegalArgumentException) {
                logger.error("Invalid sessionId format in event for job {}: {}", jobId, sidString)
            }
        }

        if (event.eventType == "FINAL_RESULT" || event.eventType == "ERROR") {
            removeFlow(jobId, jobFlows, "Job", 1.minutes.inWholeMilliseconds)
        }
    }

    /** Publishes an error event. */
    suspend fun publishError(
        jobId: UUID,
        message: String,
        details: Any? = null,
        sessionId: String? = null,
    ) {
        val event =
            JobSseEvent(
                jobId = jobId.toString(),
                eventType = "ERROR",
                message = message,
                data = details,
                sessionId = sessionId,
            )
        publishEvent(jobId, event)
    }

    @OptIn(FlowPreview::class)
    private fun launchFlowCleanup(id: UUID, flow: MutableSharedFlow<*>, type: String) {
        scope.launch {
            // Wait until there are no subscribers for the entire timeout duration
            flow.subscriptionCount
                .debounce(flowTimeout)
                .filter { it == 0 }
                .first() // This will suspend until the condition is met

            logger.info("{} flow for ID {} timed out due to inactivity. Removing.", type, id)
            if (type == "Job") {
                jobFlows.remove(id)
            } else {
                sessionFlows.remove(id)
            }
        }
    }

    private fun removeFlow(id: UUID, map: MutableMap<*, *>, type: String, delayMillis: Long) {
        scope.launch {
            if (delayMillis > 0) delay(delayMillis)
            if (map.remove(id) != null) {
                logger.info("Removed {} flow for ID: {}", type, id)
            }
        }
    }

    @PreDestroy
    fun cleanupAllFlows() {
        logger.info("Cleaning up all active job flows...")
        scope.cancel("Application shutdown cleanup")
        jobFlows.clear()
    }
}
