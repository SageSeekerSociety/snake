package org.rucca.snake.worker.domain.service

import io.opentelemetry.api.OpenTelemetry
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.util.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.rucca.snake.common.domain.exception.CompilationTimeoutException
import org.rucca.snake.common.domain.model.CompilationRequest
import org.rucca.snake.common.domain.model.JobStatus
import org.rucca.snake.common.infra.persistence.repository.CompilationJobRepository
import org.rucca.snake.common.utils.withSuspendingSpan
import org.rucca.snake.worker.config.ApplicationConfig
import org.rucca.snake.worker.infra.amqp.ResultNotifier
import org.rucca.snake.worker.infra.storage.MinioService
import org.rucca.snake.worker.utils.deleteDirectoryRecursively
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class CompileService(
    private val compilationJobRepository: CompilationJobRepository,
    private val minioService: MinioService,
    private val resultNotifier: ResultNotifier,
    private val applicationConfig: ApplicationConfig,
    openTelemetry: OpenTelemetry,
) {
    private val tracer = openTelemetry.getTracer(CompileService::class.java.name)

    private val logger = LoggerFactory.getLogger(CompileService::class.java)

    private val compileTimeoutSeconds: Long = 60 // Default timeout for compilation in seconds

    /**
     * Processes a compilation request received from the message queue. This function should be
     * called by the TaskProcessor. It handles database status updates, compilation, MinIO upload,
     * and final DB update. Throws exceptions on critical failures to trigger NACK in the caller.
     *
     * @param request The compilation request data.
     * @param jobId The unique job ID associated with this request.
     */
    suspend fun processCompilationRequest(request: CompilationRequest, jobId: String) =
        tracer.withSuspendingSpan("job.compile.process") {
            val userId = request.userId
            val sourceCodeRef = request.sourceCodeRef
            val userCompileDir =
                Paths.get(applicationConfig.dataDirectory, userId.toString(), "compile", jobId)
            val sourceFileName = "source.cpp" // Or derive from request if language varies
            val outputFileName = "program" // The final executable name
            val sourceFilePath = userCompileDir.resolve(sourceFileName)
            val outputFilePath = userCompileDir.resolve(outputFileName)
            val compiledProgramObjectKey = "programs/$userId/$outputFileName" // Object key in MinIO

            var currentStatus = JobStatus.RECEIVED
            var compileOutputText: String? = null
            var errorDetails: String? = null
            var programStorageRef: String? = null
            val startTime = Instant.now()

            try {
                // Prepare compilation directory (skip DB COMPILING state to reduce writes)
                val compileDir: Path =
                    withContext(Dispatchers.IO) {
                        try {
                            Files.createDirectories(userCompileDir)
                            logger.info(
                                "Created compile directory for job {} at {}",
                                jobId,
                                userCompileDir,
                            )
                            userCompileDir // Return the path
                        } catch (e: IOException) {
                            logger.error(
                                "Failed to create compile directory for job {}: {}",
                                jobId,
                                e.message,
                            )
                            throw RuntimeException("IO error during compilation preparation", e)
                        }
                    }

                // Download Source Code from MinIO
                logger.info(
                    "Downloading source code for job {} from MinIO key '{}' to '{}'",
                    jobId,
                    sourceCodeRef,
                    sourceFilePath,
                )

                val downloadSuccess: Boolean =
                    tracer.withSuspendingSpan("minio.download_source", ctx = Dispatchers.IO) {
                        minioService.downloadObject(sourceCodeRef, sourceFilePath)
                    }

                if (!downloadSuccess) {
                    logger.error(
                        "Failed to download source code from MinIO (key: {}) for job {}",
                        sourceCodeRef,
                        jobId,
                    )
                    currentStatus = JobStatus.ERROR
                    errorDetails = "Failed to download source code from storage."
                    // Re-throw to trigger NACK and let finally perform the single final DB update
                    throw RuntimeException("Failed to download source code for job $jobId")
                }

                logger.info("Successfully downloaded source code for job {}", jobId)

                // Execute Compilation
                val compileResult = executeCompileCommand(sourceFilePath, outputFilePath)
                compileOutputText =
                    compileResult.output // Store compiler output regardless of success

                if (!compileResult.success) {
                    logger.warn(
                        "Compilation failed for job {}. Exit code: {}",
                        jobId,
                        compileResult.exitCode,
                    )
                    currentStatus = JobStatus.FAILED
                    errorDetails =
                        "Compilation failed with exit code ${compileResult.exitCode}. Output:\n${compileOutputText.take(1024)}" // Truncate long output
                } else {
                    logger.info("Compilation successful for job {}", jobId)

                    // 5. Upload successful result to MinIO
                    val etag =
                        tracer.withSuspendingSpan("minio.upload_program") {
                            minioService.uploadFile(compiledProgramObjectKey, outputFilePath)
                        }
                    if (etag == null) {
                        logger.error("Failed to upload compiled program to MinIO for job {}", jobId)
                        currentStatus = JobStatus.ERROR // Indicate an infrastructure error
                        errorDetails = "Failed to upload result to object storage."
                    } else {
                        currentStatus = JobStatus.SUCCESS
                        programStorageRef = compiledProgramObjectKey // Store the reference
                        logger.info(
                            "Successfully uploaded program for job {} to MinIO: {}",
                            jobId,
                            compiledProgramObjectKey,
                        )

                        // Optional: Invalidate cache (deferred)
                        // Currently skipped as per requirement
                    }
                }
            } catch (cte: CompilationTimeoutException) {
                // Catch the specific timeout exception from executeCompileCommand
                logger.error("Compilation timed out for job {}", jobId)
                currentStatus = JobStatus.FAILED // Or TLE if you have a specific status
                errorDetails = cte.message
                compileOutputText = compileOutputText ?: "[Compilation Timed Out]"
            } catch (e: Exception) {
                logger.error(
                    "Unhandled exception during compilation for job {}: {}",
                    jobId,
                    e.message,
                    e,
                )
                currentStatus = JobStatus.ERROR // Worker internal error
                errorDetails = "Internal worker error during compilation: ${e.message}"
                // Re-throw to ensure NACK
                throw e
            } finally {
                // Final DB Update (single write including startTime)
                updateJobStatus(
                    jobId,
                    currentStatus,
                    startTime = startTime,
                    endTime = Instant.now(),
                    compilerOutput = compileOutputText,
                    programStorageRef = programStorageRef,
                    errorDetails = errorDetails,
                )

                // Notify Result via AMQP
                resultNotifier.notifyCompilationResult(
                    UUID.fromString(jobId),
                    request.userId,
                    currentStatus,
                    programStorageRef,
                )

                // Cleanup local compile directory
                cleanupCompileDir(userCompileDir, jobId)
            }
        }

    /** Executes the compilation command in a separate process with a timeout. */
    private suspend fun executeCompileCommand(
        sourceFile: Path,
        outputFile: Path,
    ): CompileCommandResult =
        tracer.withSuspendingSpan("compile.command.execute") {
            val compilerPath = applicationConfig.compilerPath
            val compilerParameters = applicationConfig.compilerParameter
            val command = mutableListOf(compilerPath)
            command.addAll(compilerParameters)
            command.add(sourceFile.toAbsolutePath().toString())
            command.add("-o")
            command.add(outputFile.toAbsolutePath().toString())

            logger.info("Executing compile command: {}", command.joinToString(" "))

            var processOutput = ""
            var exitCode = -1

            try {
                // Run compilation within IO context and with a timeout
                withTimeout(TimeUnit.SECONDS.toMillis(compileTimeoutSeconds)) {
                    withContext(Dispatchers.IO) {
                        val processBuilder = ProcessBuilder(command)
                        processBuilder.redirectErrorStream(true) // Merge stdout and stderr
                        val process = processBuilder.start()

                        // Read output asynchronously to prevent buffer blocking
                        val outputGobbler =
                            CoroutineScope(Dispatchers.IO).async {
                                process.inputStream.bufferedReader().use { it.readText() }
                            }

                        exitCode = process.waitFor() // Wait for process completion
                        processOutput = outputGobbler.await() // Get the full output
                        logger.debug(
                            "Compile process for {} finished with exit code {}. Output:\n{}",
                            sourceFile,
                            exitCode,
                            processOutput.take(500),
                        )
                        //                    exitCode == 0 // Return success status
                    }
                }

                CompileCommandResult(
                    exitCode = exitCode,
                    output = processOutput,
                    success = (exitCode == 0),
                )
            } catch (_: TimeoutCancellationException) {
                // Catch the specific exception from withTimeout
                logger.warn("Compilation command timed out for source file: {}", sourceFile)
                // Throw *our* custom exception to signal timeout clearly to the caller
                throw CompilationTimeoutException(
                    "Compilation exceeded $compileTimeoutSeconds seconds"
                )
            } catch (ioe: IOException) {
                logger.error(
                    "IOException during compilation command execution for {}: {}",
                    sourceFile,
                    ioe.message,
                )
                CompileCommandResult(
                    exitCode = -1,
                    output = "Failed to start compiler: ${ioe.message}",
                    success = false,
                )
            } catch (e: Exception) {
                // Catch other potential exceptions from process handling
                logger.error(
                    "Exception during compilation command execution for {}: {}",
                    sourceFile,
                    e.message,
                    e,
                )
                CompileCommandResult(
                    exitCode = -1,
                    output = "Internal error during compilation: ${e.message}",
                    success = false,
                )
            }
        }

    /**
     * Helper function to update the job status in the database. Use single-shot update to avoid
     * read-modify-write and reduce DB load.
     */
    private suspend fun updateJobStatus(
        jobId: String,
        status: JobStatus,
        startTime: Instant? = null,
        endTime: Instant? = null,
        compilerOutput: String? = null,
        programStorageRef: String? = null,
        errorDetails: String? = null,
    ) {
        withContext(Dispatchers.IO) {
            try {
                val rows =
                    compilationJobRepository.updateFinalByIdIfStatus(
                        jobId = UUID.fromString(jobId),
                        expectedStatus = JobStatus.PENDING,
                        status = status,
                        startTime = startTime,
                        endTime = endTime,
                        compilerOutput = compilerOutput,
                        programStorageRef = programStorageRef,
                        errorDetails = errorDetails,
                        workerNodeId = applicationConfig.nodeId,
                    )
                if (rows == 0) {
                    logger.warn(
                        "No rows updated for compilation job {}. It may have been already finalized or not found.",
                        jobId,
                    )
                } else {
                    logger.info("Updated job {} status to {}", jobId, status)
                }
            } catch (e: Exception) {
                logger.error("Failed to update database for job {}: {}", jobId, e.message, e)
            }
        }
    }

    private suspend fun cleanupCompileDir(dir: Path, jobId: String) {
        withContext(Dispatchers.IO + NonCancellable) {
            try {
                // The suspend function now calls the non-suspend, blocking helper.
                deleteDirectoryRecursively(dir)
                logger.info("Cleaned up compile directory for job {}: {}", jobId, dir)
            } catch (e: Exception) {
                logger.error("Failed to cleanup compile directory for job {}: {}", jobId, e.message)
            }
        }
    }

    // Internal data class for compilation result
    private data class CompileCommandResult(
        val exitCode: Int,
        val output: String,
        val success: Boolean,
    )
}
