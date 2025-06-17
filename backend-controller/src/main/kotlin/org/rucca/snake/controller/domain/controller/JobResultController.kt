package org.rucca.snake.controller.domain.controller

import java.util.*
import org.rucca.cheese.auth.annotation.Guard
import org.rucca.snake.controller.domain.model.ApiError
import org.rucca.snake.controller.domain.model.ApiResponse
import org.rucca.snake.controller.domain.model.BatchQueryResultItem
import org.rucca.snake.controller.domain.model.JobResultDto
import org.rucca.snake.controller.domain.service.JobQueryService
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/jobs")
class JobResultController(private val jobQueryService: JobQueryService) {
    private val logger = LoggerFactory.getLogger(JobResultController::class.java)

    @Guard("query", "job")
    @GetMapping("/{jobId}")
    suspend fun getJobResult(@PathVariable jobId: String): ResponseEntity<ApiResponse> {
        val jobUUID: UUID =
            try {
                UUID.fromString(jobId)
            } catch (e: IllegalArgumentException) {
                return ResponseEntity.badRequest()
                    .body(
                        ApiResponse.Error(
                            code = 400,
                            message = "Invalid job ID format.",
                            error =
                                ApiError(type = "VALIDATION_ERROR", details = "Invalid UUID format"),
                        )
                    )
            }

        try {
            val resultDto: JobResultDto? =
                jobQueryService.getJobStatusAndResult(jobUUID) // Direct suspend call
            return if (resultDto != null) { // Return directly
                logger.debug("Returning result for job: {}", jobId)
                ResponseEntity.ok(ApiResponse.Success<Any>(data = resultDto))
            } else {
                logger.info("Job not found: {}", jobId)
                ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(
                        ApiResponse.Error(
                            code = 404,
                            message = "Job not found.",
                            error = ApiError(type = "NOT_FOUND", details = "Job ID: $jobId"),
                        )
                    )
            }
        } catch (e: Exception) {
            logger.error("Error querying job result for ID {}: {}", jobId, e.message)
            throw e
        }
    }

    @Guard("query", "job")
    @GetMapping
    suspend fun getBatchJobResults(
        @RequestParam("jobIds") jobIdsStr: String?
    ): ResponseEntity<ApiResponse> {
        if (jobIdsStr.isNullOrBlank())
            return ResponseEntity.badRequest()
                .body(
                    ApiResponse.Error(
                        code = 400,
                        message = "jobIds parameter required.",
                        error = ApiError(type = "VALIDATION_ERROR"),
                    )
                )

        val jobIdStrings = jobIdsStr.split(',').mapNotNull { it.trim().takeIf { it.isNotEmpty() } }
        if (jobIdStrings.isEmpty())
            return ResponseEntity.ok(ApiResponse.Success<Any>(data = emptyList<Any>()))

        val jobUUIDs =
            jobQueryService.parseJobIds(jobIdStrings) // This helper is likely not suspend
        if (jobUUIDs.isEmpty() && jobIdStrings.isNotEmpty())
            return ResponseEntity.badRequest()
                .body(
                    ApiResponse.Error(
                        code = 400,
                        message = "All job IDs invalid.",
                        error = ApiError(type = "VALIDATION_ERROR"),
                    )
                )

        try {
            val results: List<BatchQueryResultItem> =
                jobQueryService.getBatchJobStatusAndResults(jobUUIDs) // Direct suspend call
            logger.debug("Returning batch results for {} valid job IDs.", jobUUIDs.size)
            return ResponseEntity.ok(ApiResponse.Success<Any>(data = results)) // Return directly
        } catch (e: Exception) {
            logger.error(
                "Error during batch job query for IDs: {}, error: {}",
                jobIdsStr,
                e.message,
                e,
            )
            throw e
        }
    }
}
