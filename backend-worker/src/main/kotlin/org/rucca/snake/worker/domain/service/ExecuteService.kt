package org.rucca.snake.worker.domain.service

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration
import java.time.Instant
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.rucca.snake.common.domain.model.ExecutionRequest
import org.rucca.snake.common.domain.model.JobStatus
import org.rucca.snake.common.infra.persistence.repository.ExecutionJobRepository
import org.rucca.snake.worker.config.ApplicationConfig
import org.rucca.snake.worker.infra.amqp.ResultNotifier
import org.rucca.snake.worker.infra.storage.MinioService
import org.rucca.snake.worker.utils.deleteDirectoryRecursively
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service

@Service
class ExecuteService(
    private val executionJobRepository: ExecutionJobRepository,
    private val cacheManager: CacheManager,
    private val resultNotifier: ResultNotifier,
    private val applicationConfig: ApplicationConfig,
    private val minioService: MinioService,
    private val redisTemplate: StringRedisTemplate, // Added RedisTemplate
    @Value("\${memory.ttl.minutes}") private val memoryTtlMinutes: Long, // Added memory TTL
    @Value("\${memory.max.size.kb}") private val memoryMaxSizeKb: Int, // Added memory max size
) {
    private val logger = LoggerFactory.getLogger(ExecuteService::class.java)

    // Semaphore to limit concurrent nsjail processes
    private val nsjailSemaphore =
        Semaphore(permits = applicationConfig.concurrency.nsjailPermits) // Get permits from config

    // Regex to parse the custom Cgroup Stats log line
    // Example: [I] Cgroup Stats: CPU_usec=12896 MEM_peak_bytes=2220032 (user=8928, system=3968)
    private val cgroupStatsPattern: Pattern =
        Pattern.compile(
            """.*Cgroup Stats: CPU_usec=(-?\d+) MEM_peak_bytes=(-?\d+).*"""
            // Add capturing groups for user/system if needed: \(user=(-?\d+), system=(-?\d+)\)
        )

    /**
     * Processes an execution request received from the message queue. Handles getting the program,
     * running nsjail, parsing stats, and updating the DB. Throws exceptions on critical failures to
     * trigger NACK.
     *
     * @param request The execution request data.
     * @param jobId The unique job ID.
     */
    suspend fun processExecutionRequest(request: ExecutionRequest, jobId: String) {
        // Retrieve new fields from request
        val sessionId = request.sessionId
        val tickNumber = request.tickNumber
        // val requestingUserId = request.currentUserId // Available if needed
        val aiOwnerUserId = request.aiOwnerUserId // Use this for memory keys and job ownership

        val userDataDir =
            Paths.get(
                applicationConfig.dataDirectory,
                aiOwnerUserId.toString(),
            ) // Base dir for user data
        val executionDir =
            userDataDir.resolve("execute").resolve(jobId) // Unique dir for this execution run
        val inputFile = executionDir.resolve("input.txt")
        val logFile = executionDir.resolve("nsjail.log")
        val programFileName = "program"
        val programPathInExecDir = userDataDir.resolve(programFileName)
        val minioObjectKey = "programs/$aiOwnerUserId/$programFileName" // Key in MinIO

        var currentStatus = JobStatus.RECEIVED
        // This will store the 'action' part of the AI output
        var action = ""
        var newMemoryData: String? = null // Raw from AI, Base64 encoded
        var memoryDataForRedis: String? = null // Validated memory data for Redis

        var cpuTimeSeconds: Double? = null
        var memoryKb: Long? = null
        var exitCode: Int? = null
        var sandboxLogContent: String? = null // Store log content if needed for debugging or result
        var errorDetails: String? = null
        val startTime = Instant.now()

        // Initialize previousMemoryData
        var previousMemoryData = "" // Default to empty string
        if (tickNumber > 0) { // Assuming tick numbers start from 0 or 1 for game ticks
            val previousTickNumber = tickNumber - 1
            val redisKey = "session:${sessionId}:memory:${aiOwnerUserId}:${previousTickNumber}"
            try {
                previousMemoryData = redisTemplate.opsForValue().get(redisKey) ?: ""
                logger.info(
                    "Retrieved previous memory for job {} from Redis key {}",
                    jobId,
                    redisKey,
                )
            } catch (e: Exception) {
                logger.error(
                    "Redis error retrieving previous memory for job {}: {}. Key: {}",
                    jobId,
                    e.message,
                    redisKey,
                    e,
                )
                // Fail the job as per requirements
                updateJobStatus(
                    jobId,
                    JobStatus.ERROR,
                    errorDetails = "Failed to read previous memory from Redis: ${e.message}",
                    endTime = Instant.now(),
                )
                resultNotifier.notifyExecutionResult(
                    UUID.fromString(jobId),
                    aiOwnerUserId,
                    JobStatus.ERROR,
                    null,
                    null, // newMemoryData
                    sessionId,
                    tickNumber,
                    // errorDetails = "Failed to read previous memory from Redis: ${e.message}" //
                    // DTO needs update for this
                )
                throw RuntimeException(
                    "Failed to read previous memory from Redis for job $jobId",
                    e,
                )
            }
        }

        val decodedPreviousMemory =
            try {
                if (previousMemoryData.isNotBlank()) Base64.getDecoder().decode(previousMemoryData)
                else byteArrayOf()
            } catch (e: IllegalArgumentException) {
                logger.warn(
                    "Previous memory data for job {} is not valid Base64, using empty.",
                    jobId,
                )
                byteArrayOf()
            }

        try {
            // 1. Update DB Status to RUNNING
            updateJobStatus(jobId, JobStatus.RUNNING, startTime = startTime)
            currentStatus = JobStatus.RUNNING

            // 2. Ensure execution directory exists
            withContext(Dispatchers.IO) {
                try {
                    Files.createDirectories(executionDir)
                } catch (e: IOException) {
                    logger.error(
                        "Failed to create execution directory for job {}: {}",
                        jobId,
                        e.message,
                    )
                    throw RuntimeException("IO error during execution preparation", e)
                }
            }

            // 3. Get Program Path (from cache or download from MinIO)
            // This function handles cache checking and download internally
            val programPathInCache = cacheManager.getProgramPath(aiOwnerUserId, minioObjectKey)
            if (programPathInCache == null || !Files.exists(programPathInCache)) {
                logger.error(
                    "Failed to obtain program binary for user {} (job {})",
                    aiOwnerUserId,
                    jobId,
                )
                throw RuntimeException("Could not get program binary") // Critical failure
            }
            // We need the program inside the chroot dir for nsjail.
            // Simplest way: copy the cached program into the *execution-specific* user data dir.
            // Nsjail will then chroot into userDataDir.
            withContext(Dispatchers.IO) {
                try {
                    // Copy from cache to the *parent* data directory (/app/data/{userId}/program)
                    // nsjail will chroot to /app/data/{userId}
                    Files.copy(
                        programPathInCache,
                        programPathInExecDir,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    )
                    // Make it executable (important!)
                    programPathInExecDir
                        .toFile()
                        .setExecutable(true, true) // Owner and group execute
                    logger.info(
                        "Copied program for job {} to execution dir {}",
                        jobId,
                        programPathInExecDir,
                    )
                } catch (e: Exception) {
                    logger.error(
                        "Failed to copy program to execution dir for job {}: {}",
                        jobId,
                        e.message,
                    )
                    throw RuntimeException("Failed to prepare program for execution", e)
                }
            }

            // 4. Prepare Input File
            withContext(Dispatchers.IO) {
                try {
                    val aiInputContentBytes =
                        request.inputData.toByteArray() + "\n".toByteArray() + decodedPreviousMemory
                    Files.write(inputFile, aiInputContentBytes)
                    logger.info("Prepared input file for job {} at {}", jobId, inputFile)
                } catch (e: IOException) {
                    logger.error("Failed to write input file for job {}: {}", jobId, e.message)
                    throw RuntimeException("IO error writing input file", e)
                }
            }

            // 5. Execute in Sandbox (using Semaphore)
            val nsjailResult: NsjailResult =
                nsjailSemaphore.withPermit {
                    logger.info("Acquired nsjail permit for job {}", jobId)
                    val res =
                        runNsjail(
                            userId = aiOwnerUserId, // Pass aiOwnerUserId
                            jobId = jobId, // Pass jobId for logging context if needed in runNsjail
                            userDataDir = userDataDir, // Chroot target
                            logFilePath = logFile,
                            inputFile = inputFile, // Redirect stdin from this file
                            programName = programFileName, // Program to execute relative to chroot
                            request = request, // Pass request for resource limits
                        )
                    logger.info("Released nsjail permit for job {}", jobId)
                    res // Return the result
                }

            // 6. Process Nsjail Result
            exitCode = nsjailResult.exitCode
            // programOutput is now split into action and newMemoryData
            // programOutput = nsjailResult.output // Old way
            sandboxLogContent = nsjailResult.logContent // Store for potential use/debugging

            var rawMemoryFromAI: String? = null
            if (nsjailResult.output.isNotBlank()) {
                val lines = nsjailResult.output.lines()
                action = lines.firstOrNull()?.trim() ?: ""
                if (lines.size > 1) {
                    rawMemoryFromAI = lines.drop(1).joinToString(separator = "\n")
                }
            }
            logger.info(
                "Job {}: Parsed Action='{}', NewMemoryData (raw from AI)='{}'",
                jobId,
                action,
                rawMemoryFromAI?.take(100),
            )
            newMemoryData =
                rawMemoryFromAI?.let { Base64.getEncoder().encodeToString(it.toByteArray()) }

            // Memory Size Limit Check (before storing to Redis)
            memoryDataForRedis = newMemoryData
            if (newMemoryData != null) {
                try {
                    val decodedBytes = Base64.getDecoder().decode(newMemoryData)
                    val memorySizeBytes = decodedBytes.size
                    if (memorySizeBytes > memoryMaxSizeKb * 1024) {
                        logger.warn(
                            "Job {}: New memory data (decoded {} bytes) exceeds limit of {} KB. Discarding memory.",
                            jobId,
                            memorySizeBytes,
                            memoryMaxSizeKb,
                        )
                        currentStatus =
                            JobStatus.ERROR // Or a custom status like JobStatus.MEMORY_LIMIT_USER
                        errorDetails =
                            (errorDetails ?: "") + " Produced memory data exceeded size limit."
                        memoryDataForRedis = null // Do not store oversized memory
                    }
                } catch (e: IllegalArgumentException) {
                    logger.warn(
                        "Job {}: New memory data is not valid Base64. Discarding memory. Error: {}",
                        jobId,
                        e.message,
                    )
                    currentStatus = JobStatus.ERROR
                    errorDetails =
                        (errorDetails ?: "") + " Produced memory data is not valid Base64."
                    memoryDataForRedis = null
                }
            }

            // Store New Memory to Redis (if valid and job was successful so far)
            // We check currentStatus before nsjail result processing, so if it's already ERROR
            // (e.g. from mem size check), don't store
            if (
                memoryDataForRedis != null &&
                    currentStatus != JobStatus.ERROR &&
                    nsjailResult.exitCode == 0
            ) {
                val redisKey = "session:${sessionId}:memory:${aiOwnerUserId}:${tickNumber}"
                try {
                    redisTemplate
                        .opsForValue()
                        .set(redisKey, memoryDataForRedis, Duration.ofMinutes(memoryTtlMinutes))
                    logger.info(
                        "Stored new memory for job {} to Redis key {} with TTL {} mins",
                        jobId,
                        redisKey,
                        memoryTtlMinutes,
                    )
                } catch (e: Exception) {
                    logger.error(
                        "Redis error storing new memory for job {}: {}. Key: {}",
                        jobId,
                        e.message,
                        redisKey,
                        e,
                    )
                    // Fail the job
                    currentStatus =
                        JobStatus.ERROR // Update status before final updateJobStatus call
                    errorDetails =
                        (errorDetails ?: "") + " Failed to store new memory to Redis: ${e.message}"
                    // We won't throw here to allow finally block to run, but status is ERROR.
                    // The existing resultNotifier in finally will pick up the ERROR status.
                }
            }

            // Output the sandbox log for debugging
            logger.debug(
                "Nsjail log for job {}: {}",
                jobId,
                nsjailResult.logContent, // Limit log size for debug output
            )

            // Parse stats from log
            val stats = parseCgroupStatsFromLog(nsjailResult.logContent)
            cpuTimeSeconds = stats?.cpuTimeSeconds
            memoryKb = stats?.memoryPeakKb

            // Determine final status based on exit code, limits, and potentially signals
            if (currentStatus != JobStatus.ERROR) {
                currentStatus = determineFinalStatus(nsjailResult, request, stats)
            }

            if (currentStatus != JobStatus.SUCCESS) {
                errorDetails = buildErrorDetails(currentStatus, nsjailResult, stats)
                logger.warn(
                    "Execution job {} finished with status: {}. ExitCode: {}, CPU: {}s, Mem: {}KB. Error: {}",
                    jobId,
                    currentStatus,
                    exitCode,
                    cpuTimeSeconds ?: "N/A",
                    memoryKb ?: "N/A",
                    errorDetails.take(200),
                )
            } else {
                logger.info(
                    "Execution job {} finished with status: {}. ExitCode: {}, CPU: {}s, Mem: {}KB.",
                    jobId,
                    currentStatus,
                    exitCode,
                    cpuTimeSeconds ?: "N/A",
                    memoryKb ?: "N/A",
                )
            }
        } catch (e: TimeoutCancellationException) {
            // This would typically be caught if the semaphore wait times out,
            // or if some surrounding operation had a timeout.
            logger.error("Operation timed out for job {} (could be semaphore wait or other)", jobId)
            currentStatus = JobStatus.ERROR // Or maybe a specific timeout status
            errorDetails = "Worker operation timed out: ${e.message}"
            // Re-throw
            throw e
        } catch (e: Exception) {
            logger.error("Unhandled exception during execution for job {}: {}", jobId, e.message, e)
            currentStatus = JobStatus.ERROR // Worker internal error
            errorDetails = "Internal worker error during execution: ${e.message}"
            // Re-throw to ensure NACK
            throw e
        } finally {
            val logFileKey = "logs/$aiOwnerUserId/$jobId/nsjail.log"
            var finalLogRef: String? = null
            if (Files.exists(logFile)) {
                try {
                    minioService
                        .uploadFile(
                            objectKey = logFileKey,
                            filePath = logFile,
                            contentType = "text/plain",
                        )
                        ?.also { finalLogRef = logFileKey }
                    logger.info("Uploaded nsjail log file for job {} to MinIO", jobId)
                } catch (e: Exception) {
                    logger.error(
                        "Failed to upload nsjail log file for job {}: {}",
                        jobId,
                        e.message,
                    )
                }
            }

            // 7. Final DB Update
            updateJobStatus(
                jobId = jobId,
                status = currentStatus,
                endTime = Instant.now(),
                programOutput = action, // <<< MODIFIED HERE
                cpuTimeSeconds = cpuTimeSeconds,
                memoryKb = memoryKb,
                exitCode = exitCode,
                sandboxLogRef = finalLogRef, // Optional
                errorDetails = errorDetails,
            )

            // Notify result
            resultNotifier.notifyExecutionResult(
                jobId = UUID.fromString(jobId),
                userId = aiOwnerUserId, // Use aiOwnerUserId
                status = currentStatus,
                action = action, // <<< ADDED HERE
                newMemoryData =
                    if (currentStatus == JobStatus.SUCCESS) memoryDataForRedis else null,
                sessionId = sessionId,
                tickNumber = tickNumber,
                // errorDetails = errorDetails // DTO needs update for this
            )

            // 8. Cleanup execution-specific directory (input, log)
            withContext(Dispatchers.IO + NonCancellable) {
                try {
                    if (Files.exists(executionDir)) {
                        deleteDirectoryRecursively(executionDir)
                        logger.info(
                            "Cleaned up execution directory for job {}: {}",
                            jobId,
                            executionDir,
                        )
                    }
                    // Also remove the copied program file from the parent data directory
                    Files.deleteIfExists(programPathInExecDir)
                    logger.info(
                        "Cleaned up copied program file for job {}: {}",
                        jobId,
                        programPathInExecDir,
                    )
                } catch (e: Exception) {
                    logger.error(
                        "Failed to cleanup execution directory or program for job {}: {}",
                        jobId,
                        e.message,
                    )
                }
            }
        }
    }

    /** Runs the nsjail process. */
    private suspend fun runNsjail(
        userId: Long, // For context
        jobId: String, // For context
        userDataDir: Path, // The directory to chroot into (/app/data/{userId})
        logFilePath: Path,
        inputFile: Path, // File to redirect stdin from
        programName: String, // Relative path inside chroot (e.g., "program")
        request: ExecutionRequest, // Contains resource limits
    ): NsjailResult {
        val nsjailPath = applicationConfig.nsjail.path
        val baseParameters = applicationConfig.nsjail.baseParameters

        val command = mutableListOf(nsjailPath)
        command.addAll(baseParameters)

        // Add dynamic parameters
        command.add("--chroot")
        command.add(userDataDir.toAbsolutePath().toString())
        command.add("--log")
        command.add(logFilePath.toAbsolutePath().toString())

        // Add resource limits from request
        // Convert CPU time limit to seconds for rlimit_cpu (or use time_limit for wall time)
        // rlimit_cpu expects integer seconds
        val rlimitCpuSeconds = request.cpuTimeLimitSeconds.coerceAtLeast(1.0).toInt().toString()
        command.add("--rlimit_cpu")
        command.add(rlimitCpuSeconds)

        command.add("--time_limit")
        command.add(request.wallTimeLimitSeconds.toString())

        val cgroupMemBytes = (request.memoryLimitKb * 1024).toString()
        command.add("--cgroup_mem_max")
        command.add(cgroupMemBytes)

        // Add the command to execute
        command.add("--")
        command.add("./$programName") // Execute the program relative to the chroot dir

        logger.info("Executing nsjail command for job {}: {}", jobId, command.joinToString(" "))

        var processOutput = ""
        var exitCode = -1
        var logContent = ""

        try {
            // Execute within IO context
            withContext(Dispatchers.IO) {
                val processBuilder = ProcessBuilder(command)
                // Redirect stdin from the prepared input file
                processBuilder.redirectInput(ProcessBuilder.Redirect.from(inputFile.toFile()))
                // Don't merge stderr to stdout, nsjail logs errors to stderr or log file
                // processBuilder.redirectErrorStream(true)

                val process = processBuilder.start()

                // Asynchronously capture stdout
                val outputGobbler =
                    CoroutineScope(Dispatchers.IO).async {
                        process.inputStream.bufferedReader().use { it.readText() }
                    }
                // Asynchronously capture stderr (nsjail's own errors, if any)
                val errorGobbler =
                    CoroutineScope(Dispatchers.IO).async {
                        process.errorStream.bufferedReader().use { it.readText() }
                    }

                // Wait for completion, use wall time limit + buffer
                val waitTimeoutMillis =
                    TimeUnit.SECONDS.toMillis(request.wallTimeLimitSeconds + 2) // Add 2s buffer

                // waitFor with timeout
                if (!process.waitFor(waitTimeoutMillis, TimeUnit.MILLISECONDS)) {
                    // Process exceeded wall time limit
                    logger.warn(
                        "Nsjail process for job {} exceeded wall time limit. Forcibly destroying.",
                        jobId,
                    )
                    try {
                        process.destroyForcibly()
                    } catch (_: Exception) {}
                    exitCode = -9 // Assign custom code for wall TLE
                    processOutput =
                        outputGobbler.await().take(10 * 1024) // Get partial output (limit size)
                    // Try to read log file even on timeout
                    logContent =
                        try {
                            Files.readString(logFilePath)
                        } catch (_: Exception) {
                            "[Log file unavailable after timeout]"
                        }
                    return@withContext // Exit IO context block
                }

                // Process completed normally or was killed by nsjail limits (e.g., rlimit_cpu)
                exitCode = process.exitValue()
                processOutput = outputGobbler.await().take(10 * 1024) // Limit output size
                val nsjailErrors = errorGobbler.await()
                if (nsjailErrors.isNotBlank()) {
                    logger.warn("Nsjail stderr for job {}: {}", jobId, nsjailErrors)
                }

                // Read the log file
                logContent =
                    try {
                        Files.readString(logFilePath)
                    } catch (e: IOException) {
                        logger.error(
                            "Failed to read nsjail log file {} for job {}: {}",
                            logFilePath,
                            jobId,
                            e.message,
                        )
                        "[Failed to read log file: ${e.message}]"
                    }
            }
        } catch (ioe: IOException) {
            logger.error("IOException during nsjail execution for job {}: {}", jobId, ioe.message)
            return NsjailResult(
                exitCode = -1,
                output = "",
                logContent = "Failed to start nsjail: ${ioe.message}",
                timedOut = false,
            )
        } catch (e: Exception) {
            logger.error(
                "Unexpected exception during nsjail execution for job {}: {}",
                jobId,
                e.message,
                e,
            )
            return NsjailResult(
                exitCode = -1,
                output = "",
                logContent = "Internal error during nsjail execution: ${e.message}",
                timedOut = false,
            )
        }

        return NsjailResult(
            exitCode = exitCode,
            output = processOutput,
            logContent = logContent,
            timedOut = (exitCode == -9),
        )
    }

    /** Parses the Cgroup stats line from the nsjail log. */
    private fun parseCgroupStatsFromLog(logContent: String?): CgroupStats? {
        if (logContent == null) return null
        val matcher = cgroupStatsPattern.matcher(logContent)
        return if (matcher.find()) {
            try {
                val cpuUsecStr = matcher.group(1)
                val memBytesStr = matcher.group(2)
                val cpuUsec = cpuUsecStr?.toLongOrNull() ?: -1
                val memBytes = memBytesStr?.toLongOrNull() ?: -1

                if (cpuUsec >= 0 && memBytes >= 0) {
                    CgroupStats(
                        cpuTimeSeconds = cpuUsec / 1_000_000.0, // Convert usec to seconds
                        memoryPeakKb = memBytes / 1024, // Convert bytes to KB
                    )
                } else {
                    logger.warn(
                        "Parsed invalid values from Cgroup stats line: CPU_usec={}, MEM_peak_bytes={}",
                        cpuUsecStr,
                        memBytesStr,
                    )
                    // Only takes valid values
                    CgroupStats(
                        cpuTimeSeconds = (cpuUsec / 1_000_000.0).takeIf { it >= 0 },
                        memoryPeakKb = (memBytes / 1024).takeIf { it >= 0 },
                    )
                }
            } catch (e: Exception) {
                logger.error("Error parsing Cgroup stats line: {}", e.message)
                null // Error during parsing
            }
        } else {
            logger.warn("Cgroup Stats line not found in nsjail log.")
            null // Pattern not found
        }
    }

    /** Checks if the log contains evidence of a Wall Clock timeout. */
    private fun isWallClockTimeout(logContent: String?): Boolean {
        if (logContent == null) return false
        // Look for the characteristic log message indicating wall clock timeout
        // The pattern is: "pid=XXX run time >= time limit (XXX >= XXX) (XXX). Killing it"
        val wallClockTimeoutRegex =
            Regex("pid=\\d+ run time >= time limit \\(\\d+ >= \\d+\\).*?Killing it")
        return wallClockTimeoutRegex.find(logContent) != null
    }

    /** Checks if the memory usage indicates a Memory Limit Exceeded condition. */
    private fun isMemoryLimitExceeded(stats: CgroupStats?, request: ExecutionRequest): Boolean {
        if (stats?.memoryPeakKb == null) return false
        // Check if memory usage was close to or at the limit (95% threshold)
        return stats.memoryPeakKb >= request.memoryLimitKb * 0.95
    }

    /** Determines the final JobStatus based on nsjail result and request limits. */
    private fun determineFinalStatus(
        result: NsjailResult,
        request: ExecutionRequest,
        stats: CgroupStats?,
    ): JobStatus {
        if (result.timedOut) {
            return JobStatus.TLE // Wall time limit exceeded
        }
        if (result.exitCode == null) {
            return JobStatus.ERROR // Should not happen if not timed out
        }

        // Check for nsjail internal errors first (common exit codes, may vary)
        return when (result.exitCode) {
            255 -> JobStatus.ERROR // Nsjail internal error
            // Add other specific nsjail error codes if known

            // Check for signals indicating resource limits (may need testing)
            // SIGXCPU (CPU time limit exceeded by rlimit_cpu) - often exit code 152 (128 + 24) or
            // similar
            152,
            (128 + SIGXCPU) -> JobStatus.TLE
            // SIGKILL (Often sent for OOM by cgroup or external killer) - exit code 137 (128 + 9)
            137,
            (128 + SIGKILL) -> {
                // Step 1: Check for Wall Clock timeout in logs
                if (isWallClockTimeout(result.logContent)) {
                    logger.info("Detected Wall Clock TLE from log content for exit code 137")
                    return JobStatus.TLE
                }

                // Step 2: Check for Memory Limit Exceeded
                if (isMemoryLimitExceeded(stats, request)) {
                    logger.info("Detected MLE from memory stats for exit code 137")
                    return JobStatus.MLE
                }

                // Step 3: Default to TLE if neither of the above conditions are met
                logger.info("Defaulting to CPU TLE for exit code 137")
                return JobStatus.TLE
            }
            // SIGSEGV (Segmentation fault) - exit code 139 (128 + 11)
            139,
            (128 + SIGSEGV) -> JobStatus.RE
            // SIGXFSZ (File size limit exceeded) - exit code 153 (128 + 25) or similar
            153,
            (128 + SIGXFSZ) -> JobStatus.OLE // Treat as Output Limit Exceeded
            // Add other relevant signals (SIGABRT, SIGBUS etc.)

            // Normal exit
            0 -> JobStatus.SUCCESS

            // Non-zero exit without specific signal or known error
            else -> JobStatus.RE // Runtime Error for other non-zero exits
        }
    }

    /** Creates a concise error detail string based on the status. */
    private fun buildErrorDetails(
        status: JobStatus,
        result: NsjailResult,
        stats: CgroupStats?,
    ): String {
        val exitInfo = "ExitCode: ${result.exitCode ?: "N/A"}"
        val timeInfo = "CPU: ${stats?.cpuTimeSeconds?.let { "%.3fs".format(it) } ?: "N/A"}"
        val memInfo = "Mem: ${stats?.memoryPeakKb?.let { "${it}KB" } ?: "N/A"}"

        return when (status) {
            JobStatus.TLE -> {
                // Provide more specific TLE information based on the exit code and log content
                val tleType =
                    when {
                        result.timedOut -> "Wall Clock Time Limit Exceeded (Process Timeout)"
                        result.exitCode == 152 || result.exitCode == (128 + SIGXCPU) ->
                            "CPU Time Limit Exceeded (SIGXCPU)"
                        result.exitCode == 137 || result.exitCode == (128 + SIGKILL) -> {
                            if (isWallClockTimeout(result.logContent)) {
                                "Wall Clock Time Limit Exceeded"
                            } else {
                                "CPU Time Limit Exceeded"
                            }
                        }

                        else -> "Time Limit Exceeded"
                    }
                "$tleType. $exitInfo, $timeInfo, $memInfo"
            }

            JobStatus.MLE -> "Memory Limit Exceeded. $exitInfo, $timeInfo, $memInfo"
            JobStatus.RE -> "Runtime Error. $exitInfo, $timeInfo, $memInfo"
            JobStatus.OLE ->
                "Output/File Size Limit Exceeded. $exitInfo" // Time/Mem less relevant here
            JobStatus.ERROR -> "Worker Error. ${result.logContent}" // Show last part of log for
            // worker errors
            else -> "Execution Failed. $exitInfo" // Generic failure
        }
    }

    /** Helper to update execution job status in DB. */
    private suspend fun updateJobStatus(
        jobId: String,
        status: JobStatus,
        startTime: Instant? = null,
        endTime: Instant? = null,
        programOutput: String? = null,
        cpuTimeSeconds: Double? = null,
        memoryKb: Long? = null,
        exitCode: Int? = null,
        sandboxLogRef: String? = null,
        errorDetails: String? = null,
    ) {
        withContext(Dispatchers.IO) {
            try {
                val job = executionJobRepository.findById(UUID.fromString(jobId)).orElse(null)

                if (job == null) {
                    logger.error("Execution job with ID {} not found for status update.", jobId)
                    return@withContext // Exit if job not found
                }

                job.status = status
                if (startTime != null && job.startExecutionTime == null)
                    job.startExecutionTime = startTime
                if (endTime != null) job.endExecutionTime = endTime
                if (programOutput != null)
                    job.programOutput = programOutput // TODO: Add OLE check/truncation?
                job.cpuTimeSeconds = cpuTimeSeconds
                job.memoryKb = memoryKb
                job.exitCode = exitCode
                job.sandboxLogRef = sandboxLogRef
                job.errorDetails = errorDetails
                // job.workerNodeId = appConfig.workerId
                executionJobRepository.save(job)
                logger.info("Updated execution job {} status to {}", jobId, status)
            } catch (e: Exception) {
                logger.error(
                    "Failed to update database for execution job {}: {}",
                    jobId,
                    e.message,
                    e,
                )
            }
        }
    }

    // Data classes for internal results
    private data class NsjailResult(
        val exitCode: Int?,
        val output: String,
        val logContent: String,
        val timedOut: Boolean = false,
    )

    private data class CgroupStats(val cpuTimeSeconds: Double?, val memoryPeakKb: Long?)

    // Define signal constants if not available easily (check your OS headers or define manually)
    companion object {
        const val SIGXCPU = 24
        const val SIGKILL = 9
        const val SIGSEGV = 11
        const val SIGXFSZ = 25
        // Add others as needed
    }
}
