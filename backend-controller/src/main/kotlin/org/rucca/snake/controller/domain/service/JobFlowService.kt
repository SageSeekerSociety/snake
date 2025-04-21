package org.rucca.snake.controller.domain.service

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.rucca.snake.controller.domain.model.JobResultDto
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
     * Creates or retrieves a SharedFlow for a given Job ID. This should be called when a job is
     * submitted or an SSE connection requests it.
     */
    fun getJobFlow(jobId: UUID): SharedFlow<JobSseEvent> {
        // Compute if absent to ensure only one flow per jobId
        return jobFlows.computeIfAbsent(jobId) { newJobId ->
            logger.info("Creating new SharedFlow for jobId: {}", newJobId)
            // replay=10: Keep the last 10 events for late subscribers
            // extraBufferCapacity=64: Allow some buffer for bursts
            // onBufferOverflow=BufferOverflow.DROP_OLDEST: Drop oldest if buffer is full
            val newFlow =
                MutableSharedFlow<JobSseEvent>(
                    replay = 10,
                    extraBufferCapacity = 64,
                    onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
                )

            // Launch a cleanup task for this specific flow when it's created
            launchFlowCleanup(newJobId, newFlow)
            newFlow
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
        } else {
            // This might happen if the result comes after the flow has timed out/been removed
            logger.warn("Attempted to publish event for non-existent or timed-out flow: {}", jobId)
            // Option: Could fetch final result from DB and create a short-lived flow? Or just
            // ignore.
        }
    }

    /**
     * Publishes the final result for a job and potentially marks the flow for cleanup. Can be
     * called by ResultListener or SseController when job completion is confirmed via DB.
     */
    suspend fun publishFinalResult(jobId: UUID, resultDto: JobResultDto) {
        val event =
            JobSseEvent(
                jobId = jobId.toString(),
                eventType = "FINAL_RESULT",
                status = resultDto.status,
                data = resultDto, // Send the full final result DTO
            )
        publishEvent(jobId, event)
        // Consider removing the flow shortly after publishing the final result
        // Or rely on the inactivity timeout
        // removeFlow(jobId, delayMillis = 10000) // Remove after 10 seconds
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
