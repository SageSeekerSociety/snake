package org.rucca.snake.controller.domain.service

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
            launchFlowCleanup(newJobId, newFlow)
            newFlow
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
        val flow = jobFlows[jobId]
        if (flow != null) {
            logger.debug("Publishing event for jobId {}: {}", jobId, event.eventType)
            flow.emit(event)

            if (event.eventType == "FINAL_RESULT" || event.eventType == "ERROR") {
                logger.info(
                    "Terminal event '{}' received for job {}. Scheduling flow for cleanup.",
                    event.eventType,
                    jobId,
                )
                // Delay removal to allow subscribers to receive the final event
                removeFlow(jobId, delayMillis = 1.minutes.inWholeMilliseconds)
            }
        } else {
            // This might happen if the result comes after the flow has timed out/been removed
            logger.warn("Attempted to publish event for non-existent or timed-out flow: {}", jobId)
            // Option: Could fetch final result from DB and create a short-lived flow? Or just
            // ignore.
        }
    }

    /** Publishes an error event. */
    suspend fun publishError(jobId: UUID, message: String, details: Any? = null) {
        val event =
            JobSseEvent(
                jobId = jobId.toString(),
                eventType = "ERROR",
                message = message,
                data = details,
            )
        publishEvent(jobId, event)
        // Also consider removing flow after error
        // removeFlow(jobId, delayMillis = 10000)
    }

    /** Launches a coroutine that monitors a flow for inactivity and removes it. */
    @OptIn(FlowPreview::class)
    private fun launchFlowCleanup(jobId: UUID, flow: MutableSharedFlow<JobSseEvent>) {
        scope.launch {
            try {
                // Wait for subscribers for a short period, then monitor activity
                var hasSubscribers = false
                val subscriberTimeout =
                    flowTimeout / 2 // Wait half the timeout for initial subscriber
                withTimeoutOrNull(subscriberTimeout) {
                    flow.subscriptionCount
                        .filter { it > 0 }
                        .first() // Wait until at least one subscriber joins
                    hasSubscribers = true
                }

                if (!hasSubscribers) {
                    logger.info(
                        "Flow for jobId {} timed out waiting for initial subscribers. Removing.",
                        jobId,
                    )
                    removeFlow(jobId)
                    return@launch
                }

                // Monitor for inactivity (no subscribers) using debounce
                // Debounce emits only after a specified duration of silence
                flow.subscriptionCount
                    .debounce(flowTimeout) // Emit only if count stays 0 for flowTimeout duration
                    .filter { it == 0 } // Only proceed if the count is 0 after the debounce period
                    .firstOrNull() // Take the first occurrence of inactivity timeout

                // If we reach here, it means the flow was inactive (0 subscribers) for the timeout
                // duration
                logger.info(
                    "Flow for jobId {} timed out due to inactivity ({} duration). Removing.",
                    jobId,
                    flowTimeout,
                )
                removeFlow(jobId)
            } catch (e: CancellationException) {
                logger.info("Flow cleanup task for jobId {} cancelled.", jobId)
                removeFlow(jobId) // Ensure removal on cancellation
            } catch (e: Exception) {
                logger.error("Error in flow cleanup task for jobId {}: {}", jobId, e.message, e)
                removeFlow(jobId) // Attempt removal on error
            }
        }
    }

    /** Removes the flow from the map. */
    private fun removeFlow(jobId: UUID, delayMillis: Long = 0) {
        // Optional delay before removal
        if (delayMillis > 0) {
            scope.launch {
                delay(delayMillis)
                val removedFlow = jobFlows.remove(jobId)
                if (removedFlow != null) {
                    logger.info("Delayed removal of flow for jobId: {}", jobId)
                }
            }
        } else {
            val removedFlow = jobFlows.remove(jobId)
            if (removedFlow != null) {
                logger.info("Removed flow for jobId: {}", jobId)
            }
        }
    }

    // Called during application shutdown
    fun cleanupAllFlows() {
        logger.info("Cleaning up all active job flows...")
        scope.cancel("Application shutdown cleanup")
        jobFlows.clear()
    }
}
