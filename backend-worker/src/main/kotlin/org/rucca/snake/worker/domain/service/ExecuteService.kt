package org.rucca.snake.worker.domain.service

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.context.Context
import io.opentelemetry.extension.kotlin.asContextElement
import jakarta.annotation.PostConstruct
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern
import kotlin.random.Random
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.sync.Semaphore
import org.rucca.snake.common.domain.model.ExecutionRequest
import org.rucca.snake.common.domain.model.ExecutionResultNotification
import org.rucca.snake.common.domain.model.JobStatus
import org.rucca.snake.common.infra.persistence.repository.ExecutionJobRepository
import org.rucca.snake.common.utils.recordSuspendable
import org.rucca.snake.common.utils.withSpan
import org.rucca.snake.common.utils.withSuspendingSpan
import org.rucca.snake.worker.config.ApplicationConfig
import org.rucca.snake.worker.infra.amqp.ResultNotifier
import org.rucca.snake.worker.infra.storage.MinioService
import org.rucca.snake.worker.utils.deleteDirectoryRecursively
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ExecuteService(
    private val executionJobRepository: ExecutionJobRepository,
    private val cacheManager: CacheManager,
    private val resultNotifier: ResultNotifier,
    private val applicationConfig: ApplicationConfig,
    private val minioService: MinioService,
    private val redisTemplate: StringRedisTemplate,
    private val meterRegistry: MeterRegistry,
    openTelemetry: OpenTelemetry,
    @Value("\${memory.ttl.minutes}") private val memoryTtlMinutes: Long,
    @Value("\${memory.max.size.kb}") private val memoryMaxSizeKb: Int,
    @Qualifier("applicationCoroutineScope") private val appScope: CoroutineScope,
    @Value("\${logging.upload.success-sample-rate:0.0}") private val successLogSampleRate: Double,
) {
    private val tracer: Tracer = openTelemetry.getTracer(ExecuteService::class.java.name)

    private val logger = LoggerFactory.getLogger(ExecuteService::class.java)

    // Semaphore to limit concurrent nsjail processes
    private val nsjailSemaphore =
        Semaphore(permits = applicationConfig.concurrency.nsjailPermits) // Get permits from config

    private val availableNsjailPermits =
        meterRegistry.gauge(
            "nsjail.permits.available",
            AtomicInteger(applicationConfig.concurrency.nsjailPermits),
        )

    // Buffered channel to decouple DB/MQ from the critical path
    private val resultChannel = Channel<ExecutionResultNotification>(capacity = Channel.BUFFERED)

    @OptIn(DelicateCoroutinesApi::class)
    @PostConstruct
    private fun startResultProcessors() {
        val workers = 4 // tune as needed
        repeat(workers) { idx ->
            appScope.launch(Dispatchers.IO + Context.current().asContextElement()) {
                val ch: ReceiveChannel<ExecutionResultNotification> = resultChannel
                logger.info("Result processor-{} started", idx)
                while (isActive && !ch.isClosedForReceive) {
                    try {
                        val batch = consumeBatch(ch, maxSize = 50, maxWaitMillis = 100)
                        if (batch.isEmpty()) continue
                        processBatch(batch)
                        batch.forEach { result ->
                            try {
                                resultNotifier.notifyExecutionResult(result)
                            } catch (e: Exception) {
                                logger.error(
                                    "AMQP notify failed for job {}: {}",
                                    result.jobId,
                                    e.message,
                                )
                            }
                        }
                        logger.info("Processed result batch size={}", batch.size)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logger.error("Error in result processing batch: {}", e.message, e)
                    }
                }
            }
        }
        logger.info("Started {} background result processors", workers)
    }

    private suspend fun consumeBatch(
        channel: ReceiveChannel<ExecutionResultNotification>,
        maxSize: Int,
        maxWaitMillis: Long,
    ): List<ExecutionResultNotification> {
        val batch = ArrayList<ExecutionResultNotification>(maxSize)
        val first = withTimeoutOrNull(maxWaitMillis) { channel.receive() }
        if (first != null) {
            batch.add(first)
            while (batch.size < maxSize) {
                val next = channel.tryReceive().getOrNull() ?: break
                batch.add(next)
            }
        }
        return batch
    }

    @Transactional
    internal fun processBatch(batch: List<ExecutionResultNotification>) {
        if (batch.isEmpty()) return
        val jobIds = batch.mapNotNull { runCatching { UUID.fromString(it.jobId) }.getOrNull() }
        if (jobIds.isEmpty()) return
        val jobs = executionJobRepository.findAllByJobIdIn(jobIds)
        if (jobs.isEmpty()) return
        val byId = jobs.associateBy { it.jobId }
        batch.forEach { result ->
            val id = runCatching { UUID.fromString(result.jobId) }.getOrNull() ?: return@forEach
            val job = byId[id] ?: return@forEach
            if (job.status != JobStatus.PENDING) return@forEach
            job.status = result.status
            if (job.startExecutionTime == null) job.startExecutionTime = result.startTime
            job.endExecutionTime = result.endTime
            job.programOutput = result.action
            job.programStderr = result.programStderr
            job.cpuTimeSeconds = result.cpuTimeSeconds
            job.memoryKb = result.memoryKb
            job.exitCode = result.exitCode
            job.sandboxLogRef = result.sandboxLogRef
            job.errorDetails = result.errorDetails
            job.workerNodeId = result.workerNodeId
        }
        executionJobRepository.saveAll(jobs)
    }

    private fun jobOutcomeCounter(status: JobStatus) =
        meterRegistry.counter(
            "job.outcomes.total",
            "type",
            "execute", // Tag: 任务类型
            "status",
            status.name, // Tag: 最终状态
        )

    private val executionTimer =
        Timer.builder("job.processing.duration")
            .tag("type", "execute")
            .description("Measures the duration of execution job processing")
            .publishPercentiles(0.5, 0.95, 0.99) // 发布 P50, P95, P99 延迟
            .register(meterRegistry)

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
        tracer.withSuspendingSpan("job.execute.process") {
            // Retrieve new fields from request
            val sessionId = request.sessionId
            val tickNumber = request.tickNumber
            // val requestingUserId = request.currentUserId // Available if needed
            val aiOwnerUserId = request.aiOwnerUserId // Use this for memory keys and job ownership

            // Enrich root span with identifiers and limits for quick triage
            Span.current().apply {
                setAttribute("job.id", jobId)
                setAttribute("user.id", aiOwnerUserId)
                setAttribute("session.id", sessionId)
                setAttribute("tick.number", tickNumber.toLong())
                request.clientRequestId?.let { setAttribute("client.request_id", it) }
                setAttribute("worker.node_id", applicationConfig.nodeId)
                setAttribute("request.cpu_limit_s", request.cpuTimeLimitSeconds)
                setAttribute("request.wall_limit_s", request.wallTimeLimitSeconds.toLong())
                setAttribute("request.mem_limit_kb", request.memoryLimitKb)
            }

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
            // Program will no longer be copied into executionDir; bind-mount cached binary instead
            val minioObjectKey = "programs/$aiOwnerUserId/$programFileName" // Key in MinIO

            executionTimer.recordSuspendable {
                var currentStatus = JobStatus.RECEIVED
                // This will store the 'action' part of the AI output
                var action = ""
                var newMemoryData: String? = null // Raw from AI, Base64 encoded
                var memoryDataForRedis: String? = null // Validated memory data for Redis

                var cpuTimeSeconds: Double? = null
                var memoryKb: Long? = null
                var exitCode: Int? = null
                var sandboxLogContent: String? =
                    null // Store log content if needed for debugging or result
                var errorDetails: String? = null
                var programStderr: String? = null
                // Set when we decide to upload logs (failure or sampled success)
                val logFileKey = "logs/$aiOwnerUserId/$jobId/nsjail.log"
                var finalLogRef: String? = null
                val startTime = Instant.now()
                var resultNotification: ExecutionResultNotification? = null

                // Initialize previousMemoryData
                var previousMemoryData = "" // Default to empty string
                if (tickNumber > 0) { // Assuming tick numbers start from 0 or 1 for game ticks
                    val previousTickNumber = tickNumber - 1
                    val redisKey =
                        "session:${sessionId}:memory:${aiOwnerUserId}:${previousTickNumber}"
                    try {
                        previousMemoryData =
                            withContext(Dispatchers.IO + Context.current().asContextElement()) {
                                tracer.withSpan("redis.get_memory") {
                                    Span.current().apply {
                                        setAttribute("session.id", sessionId)
                                        setAttribute("tick.number", previousTickNumber.toLong())
                                    }
                                    redisTemplate.opsForValue().get(redisKey) ?: ""
                                }
                            }
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
                        // Prepare async result; background processor will persist/notify
                        resultNotification =
                            ExecutionResultNotification(
                                jobId = jobId,
                                userId = aiOwnerUserId,
                                status = JobStatus.ERROR,
                                sessionId = sessionId, // No memory to store
                                tickNumber = tickNumber,
                                cpuTimeSeconds = null,
                                memoryKb = null,
                                exitCode = null,
                                action = null,
                                programStderr = null,
                                newMemoryData = null,
                                errorDetails =
                                    "Failed to read previous memory from Redis for job $jobId: ${e.message}",
                                sandboxLogRef = null,
                                clientRequestId = request.clientRequestId,
                                workerNodeId = applicationConfig.nodeId,
                                submitTime = request.timestamp,
                                startTime = startTime,
                                endTime = Instant.now(),
                            )
                        throw RuntimeException(
                            "Failed to read previous memory from Redis for job $jobId",
                            e,
                        )
                    }
                }

                val decodedPreviousMemory =
                    try {
                        if (previousMemoryData.isNotBlank())
                            Base64.getDecoder().decode(previousMemoryData)
                        else byteArrayOf()
                    } catch (e: IllegalArgumentException) {
                        logger.warn(
                            "Previous memory data for job {} is not valid Base64, using empty.",
                            jobId,
                        )
                        byteArrayOf()
                    }

                try {
                    // --- Combine pre-exec I/O into a single IO context ---
                    val programPathInCache: Path =
                        withContext(Dispatchers.IO + Context.current().asContextElement()) {
                            // a) Ensure execution directory exists
                            tracer.withSpan("fs.setup_exec_dir") {
                                try {
                                    Files.createDirectories(executionDir)
                                } catch (e: IOException) {
                                    logger.error(
                                        "Failed to create execution directory for job {}: {}",
                                        jobId,
                                        e.message,
                                    )
                                    throw RuntimeException(
                                        "IO error during execution preparation",
                                        e,
                                    )
                                }
                            }

                            // b) Get program from cache/MinIO
                            val programPath =
                                tracer.withSuspendingSpan("cache.get_program") {
                                    Span.current().setAttribute("object.key", minioObjectKey)
                                    Span.current().setAttribute("user.id", aiOwnerUserId)
                                    cacheManager.getProgramPath(aiOwnerUserId, minioObjectKey)
                                } ?: throw RuntimeException("Could not get program binary")

                            // c) Ensure cached binary perms; and write input file
                            tracer.withSpan("fs.prepare_files") {
                                try {
                                    val progFile = programPath.toFile()
                                    if (!progFile.canExecute()) {
                                        progFile.setExecutable(true, /* ownerOnly= */ false)
                                    }
                                    if (!progFile.canRead()) {
                                        progFile.setReadable(true, /* ownerOnly= */ false)
                                    }

                                    val aiInputContentBytes =
                                        request.inputData.toByteArray() +
                                            "\n".toByteArray() +
                                            decodedPreviousMemory
                                    Files.write(inputFile, aiInputContentBytes)

                                    logger.info(
                                        "Prepared input and using cached program for job {} ({}), dir {}",
                                        jobId,
                                        programPath,
                                        executionDir,
                                    )
                                } catch (e: IOException) {
                                    logger.error(
                                        "Failed to prepare files for job {}: {}",
                                        jobId,
                                        e.message,
                                    )
                                    throw RuntimeException("IO error preparing files", e)
                                }
                            }

                            programPath // return for outer scope
                        }

                    // Execute in Sandbox (using Semaphore)
                    logger.debug("Job {} WAITING for nsjail permit...", jobId)
                    val startTimeNsjailWait = System.nanoTime()

                    // Acquire permit with a span to visualize wait time, then execute
                    tracer.withSuspendingSpan("nsjail.semaphore.wait") { nsjailSemaphore.acquire() }
                    val waitTimeMs = (System.nanoTime() - startTimeNsjailWait) / 1_000_000.0
                    logger.info("Acquired nsjail permit for job {} in {} ms", jobId, waitTimeMs)
                    Span.current().setAttribute("nsjail.wait_ms", waitTimeMs)
                    availableNsjailPermits?.decrementAndGet()
                    val nsjailResult: NsjailResult =
                        try {
                            tracer.withSuspendingSpan("nsjail.execute") {
                                // annotate exec span with limits and paths
                                Span.current()
                                    .setAttribute("rlimit.cpu_s", request.cpuTimeLimitSeconds)
                                Span.current()
                                    .setAttribute(
                                        "time_limit_s",
                                        request.wallTimeLimitSeconds.toLong(),
                                    )
                                Span.current()
                                    .setAttribute("cgroup.mem_max_kb", request.memoryLimitKb)
                                Span.current().setAttribute("program.path", "/program (bind_ro)")
                                runNsjail(
                                    jobId = jobId,
                                    userDataDir = userDataDir, // Chroot target
                                    logFilePath = logFile,
                                    inputFile = inputFile, // Redirect stdin from this file
                                    programPathInCache = programPathInCache,
                                    programPathInChroot = "program",
                                    request = request, // Pass request for resource limits
                                )
                            }
                        } finally {
                            nsjailSemaphore.release()
                            availableNsjailPermits?.incrementAndGet()
                            logger.info("Released nsjail permit for job {}", jobId)
                        }

                    // Process Nsjail Result
                    exitCode = nsjailResult.exitCode
                    programStderr = nsjailResult.programStderr
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
                    // annotate nsjail result on the root span for quick filtering
                    Span.current().apply {
                        nsjailResult.exitCode?.let { setAttribute("nsjail.exit_code", it.toLong()) }
                    }
                    logger.info(
                        "Job {}: Parsed Action='{}', NewMemoryData (raw from AI)='{}'",
                        jobId,
                        action,
                        rawMemoryFromAI?.take(100),
                    )
                    newMemoryData =
                        rawMemoryFromAI?.let {
                            Base64.getEncoder().encodeToString(it.toByteArray())
                        }

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
                                // help identify frequent memory blowups
                                Span.current().apply {
                                    setAttribute("memory.discarded", true)
                                    setAttribute("memory.discard.reason", "oversize")
                                    setAttribute(
                                        "memory.discard.size_bytes",
                                        memorySizeBytes.toLong(),
                                    )
                                }
                                currentStatus = JobStatus.ERROR // Or a custom status like
                                // JobStatus.MEMORY_LIMIT_USER
                                errorDetails =
                                    (errorDetails ?: "") +
                                        " Produced memory data exceeded size limit."
                                memoryDataForRedis = null // Do not store oversized memory
                            }
                        } catch (e: IllegalArgumentException) {
                            logger.warn(
                                "Job {}: New memory data is not valid Base64. Discarding memory. Error: {}",
                                jobId,
                                e.message,
                            )
                            Span.current().apply {
                                setAttribute("memory.discarded", true)
                                setAttribute("memory.discard.reason", "invalid_base64")
                            }
                            currentStatus = JobStatus.ERROR
                            errorDetails =
                                (errorDetails ?: "") + " Produced memory data is not valid Base64."
                            memoryDataForRedis = null
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
                    // surface cgroup stats to the root span when available
                    Span.current().apply {
                        stats?.cpuTimeSeconds?.let { setAttribute("cgroup.cpu_s", it) }
                        stats?.memoryPeakKb?.let { setAttribute("cgroup.mem_peak_kb", it) }
                    }

                    // Determine final status based on exit code, limits, and potentially signals
                    if (currentStatus != JobStatus.ERROR) {
                        currentStatus = determineFinalStatus(nsjailResult, request, stats)
                    }
                    // capture final status after determination
                    Span.current().setAttribute("job.status", currentStatus.name)

                    // Store New Memory to Redis (if valid and job was successful so far)
                    // We check currentStatus before nsjail result processing, so if it's already
                    // ERROR
                    // (e.g. from mem size check), don't store
                    if (currentStatus == JobStatus.SUCCESS && memoryDataForRedis != null) {
                        val redisKey = "session:${sessionId}:memory:${aiOwnerUserId}:${tickNumber}"
                        try {
                            tracer.withSpan("redis.set_memory") {
                                // annotate redis op with session & tick for correlation (avoid full
                                // key)
                                Span.current().setAttribute("session.id", sessionId)
                                Span.current().setAttribute("tick.number", tickNumber.toLong())
                                redisTemplate
                                    .opsForValue()
                                    .set(
                                        redisKey,
                                        memoryDataForRedis,
                                        Duration.ofMinutes(memoryTtlMinutes),
                                    )
                            }
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
                            Span.current().setAttribute("redis.set_memory.error", true)
                            // Fail the job
                            currentStatus =
                                JobStatus.ERROR // Update status before final updateJobStatus call
                            errorDetails =
                                (errorDetails ?: "") +
                                    " Failed to store new memory to Redis: ${e.message}"
                            // We won't throw here to allow finally block to run, but status is
                            // ERROR.
                            // The existing resultNotifier in finally will pick up the ERROR status.
                        }
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
                        // Failure: schedule async log upload and set ref
                        if (Files.exists(logFile)) {
                            finalLogRef = logFileKey
                            scheduleAsyncLogUpload(
                                logFileKey,
                                runCatching { Files.readAllBytes(logFile) }
                                    .getOrElse { ByteArray(0) },
                            )
                        }
                    } else {
                        logger.info(
                            "Execution job {} finished with status: {}. ExitCode: {}, CPU: {}s, Mem: {}KB.",
                            jobId,
                            currentStatus,
                            exitCode,
                            cpuTimeSeconds ?: "N/A",
                            memoryKb ?: "N/A",
                        )
                        // Optional sampling for successful logs
                        if (shouldSampleSuccessLog() && Files.exists(logFile)) {
                            finalLogRef = logFileKey
                            scheduleAsyncLogUpload(
                                logFileKey,
                                runCatching { Files.readAllBytes(logFile) }
                                    .getOrElse { ByteArray(0) },
                            )
                        }
                    }
                } catch (e: TimeoutCancellationException) {
                    // This would typically be caught if the semaphore wait times out,
                    // or if some surrounding operation had a timeout.
                    logger.error(
                        "Operation timed out for job {} (could be semaphore wait or other)",
                        jobId,
                    )
                    currentStatus = JobStatus.ERROR // Or maybe a specific timeout status
                    errorDetails = "Worker operation timed out: ${e.message}"
                    Span.current().setAttribute("job.timeout", true)
                    // On failure, schedule async log upload
                    if (Files.exists(logFile)) {
                        finalLogRef = logFileKey
                        scheduleAsyncLogUpload(
                            logFileKey,
                            runCatching { Files.readAllBytes(logFile) }.getOrElse { ByteArray(0) },
                        )
                    }
                    // Prepare async result for timeout and rethrow to NACK
                    resultNotification =
                        ExecutionResultNotification(
                            jobId = jobId,
                            userId = aiOwnerUserId,
                            status = currentStatus,
                            sessionId = sessionId,
                            tickNumber = tickNumber,
                            cpuTimeSeconds = cpuTimeSeconds,
                            memoryKb = memoryKb,
                            exitCode = exitCode,
                            action = action.takeIf { it.isNotBlank() },
                            programStderr = programStderr,
                            newMemoryData = null,
                            errorDetails = errorDetails.takeUnless { it.isNullOrBlank() },
                            sandboxLogRef = finalLogRef,
                            clientRequestId = request.clientRequestId,
                            workerNodeId = applicationConfig.nodeId,
                            submitTime = request.timestamp,
                            startTime = startTime,
                            endTime = Instant.now(),
                        )
                    throw e
                } catch (e: Exception) {
                    logger.error(
                        "Unhandled exception during execution for job {}: {}",
                        jobId,
                        e.message,
                        e,
                    )
                    currentStatus = JobStatus.ERROR // Worker internal error
                    errorDetails = "Internal worker error during execution: ${e.message}"
                    Span.current().setAttribute("job.internal_error", true)
                    // On failure, schedule async log upload
                    if (Files.exists(logFile)) {
                        finalLogRef = logFileKey
                        scheduleAsyncLogUpload(
                            logFileKey,
                            runCatching { Files.readAllBytes(logFile) }.getOrElse { ByteArray(0) },
                        )
                    }
                    // Prepare async result and rethrow to ensure NACK
                    resultNotification =
                        ExecutionResultNotification(
                            jobId = jobId,
                            userId = aiOwnerUserId,
                            status = currentStatus,
                            sessionId = sessionId,
                            tickNumber = tickNumber,
                            cpuTimeSeconds = cpuTimeSeconds,
                            memoryKb = memoryKb,
                            exitCode = exitCode,
                            action = action.takeIf { it.isNotBlank() },
                            programStderr = programStderr,
                            newMemoryData = null,
                            errorDetails = errorDetails.takeUnless { it.isNullOrBlank() },
                            sandboxLogRef = finalLogRef,
                            clientRequestId = request.clientRequestId,
                            workerNodeId = applicationConfig.nodeId,
                            submitTime = request.timestamp,
                            startTime = startTime,
                            endTime = Instant.now(),
                        )
                    throw e
                } finally {
                    jobOutcomeCounter(currentStatus).increment()
                    // Enqueue for async processing (DB + MQ)
                    resultNotification =
                        resultNotification
                            ?: ExecutionResultNotification(
                                jobId = jobId,
                                userId = aiOwnerUserId,
                                status = currentStatus,
                                sessionId = sessionId,
                                tickNumber = tickNumber,
                                cpuTimeSeconds = cpuTimeSeconds,
                                memoryKb = memoryKb,
                                exitCode = exitCode,
                                action = action.takeIf { it.isNotBlank() },
                                programStderr = programStderr,
                                newMemoryData =
                                    if (currentStatus == JobStatus.SUCCESS) memoryDataForRedis
                                    else null,
                                errorDetails = errorDetails.takeUnless { it.isNullOrBlank() },
                                sandboxLogRef = finalLogRef,
                                clientRequestId = request.clientRequestId,
                                workerNodeId = applicationConfig.nodeId,
                                submitTime = request.timestamp,
                                startTime = startTime,
                                endTime = Instant.now(),
                            )
                    tracer.withSuspendingSpan("async.enqueue_result") {
                        resultChannel.send(resultNotification!!)
                    }
                    // Always cleanup files
                    withContext(Dispatchers.IO + NonCancellable) {
                        tracer.withSuspendingSpan("fs.cleanup") {
                            try {
                                if (Files.exists(executionDir)) {
                                    deleteDirectoryRecursively(executionDir)
                                    logger.info(
                                        "Cleaned up execution directory for job {}: {}",
                                        jobId,
                                        executionDir,
                                    )
                                }
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
            }
        }
    }

    private fun shouldSampleSuccessLog(): Boolean {
        if (successLogSampleRate <= 0.0) return false
        if (successLogSampleRate >= 1.0) return true
        return Random.nextDouble() < successLogSampleRate
    }

    private suspend fun updateJobStatusFromResult(result: ExecutionResultNotification) {
        tracer.withSuspendingSpan("db.update_job_from_result", ctx = Dispatchers.IO) {
            updateJobStatus(
                jobId = result.jobId,
                status = result.status,
                startTime = result.startTime,
                endTime = result.endTime,
                programOutput = result.action,
                programStderr = result.programStderr,
                cpuTimeSeconds = result.cpuTimeSeconds,
                memoryKb = result.memoryKb,
                exitCode = result.exitCode,
                sandboxLogRef = result.sandboxLogRef,
                errorDetails = result.errorDetails,
            )
        }
    }

    private fun scheduleAsyncLogUpload(objectKey: String, data: ByteArray) {
        appScope.launch(Dispatchers.IO + Context.current().asContextElement()) {
            try {
                tracer.withSuspendingSpan("minio.upload_log.async") {
                    Span.current().apply {
                        setAttribute("object.key", objectKey)
                        setAttribute("object.size_bytes", data.size.toLong())
                    }
                    minioService.uploadStream(
                        objectKey = objectKey,
                        inputStream = ByteArrayInputStream(data),
                        size = data.size.toLong(),
                        contentType = "text/plain",
                    )
                }
                logger.info("Async uploaded nsjail log to MinIO: {}", objectKey)
            } catch (e: Exception) {
                logger.error("Async log upload failed for {}: {}", objectKey, e.message)
            }
        }
    }

    /** Runs the nsjail process. */
    private suspend fun runNsjail(
        jobId: String, // For context
        userDataDir: Path, // The directory to chroot into (/app/data/{userId})
        logFilePath: Path,
        inputFile: Path, // File to redirect stdin from
        programPathInCache: Path, // Host path for cached program (bind-mount ro)
        programPathInChroot: String, // Destination path inside chroot (e.g., "program")
        request: ExecutionRequest, // Contains resource limits
    ): NsjailResult {
        val nsjailPath = applicationConfig.nsjail.path
        val baseParameters = applicationConfig.nsjail.baseParameters

        val command = mutableListOf(nsjailPath)
        command.addAll(baseParameters)

        // Add dynamic parameters
        command.add("--chroot")
        command.add(userDataDir.toAbsolutePath().toString())
        // Log to a file so that child's stderr is not mixed with nsjail logs
        command.add("--log")
        command.add(logFilePath.toAbsolutePath().toString())

        // Bind-mount the cached program into the jail as read-only
        val destInChroot = "/" + programPathInChroot.trimStart('/')
        command.add("--bindmount_ro")
        command.add("${programPathInCache.toAbsolutePath()}:$destInChroot")

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
        command.add(destInChroot) // Execute the program via absolute path inside chroot

        logger.info("Executing nsjail command for job {}: {}", jobId, command.joinToString(" "))

        var processOutput = ""
        var exitCode = -1
        var logContent = ""
        var programStderr = ""

        try {
            // Execute within IO context
            withContext(Dispatchers.IO + Context.current().asContextElement()) {
                val process =
                    tracer.withSuspendingSpan("nsjail.start_process", ctx = Dispatchers.IO) {
                        val pb = ProcessBuilder(command)
                        pb.redirectInput(ProcessBuilder.Redirect.from(inputFile.toFile()))
                        pb.start()
                    }

                coroutineScope {
                    val ctx = Context.current()
                    val outputGobbler =
                        async(Dispatchers.IO + ctx.asContextElement()) {
                            process.inputStream.bufferedReader().use { it.readText() }
                        }
                    val errorGobbler =
                        async(Dispatchers.IO + ctx.asContextElement()) {
                            process.errorStream.bufferedReader().use { it.readText() }
                        }

                    val waitTimeoutMillis =
                        TimeUnit.SECONDS.toMillis(request.wallTimeLimitSeconds + 2)

                    val finished =
                        tracer.withSuspendingSpan("nsjail.wait_for", ctx = Dispatchers.IO) {
                            process.waitFor(waitTimeoutMillis, TimeUnit.MILLISECONDS)
                        }

                    if (!finished) {
                        tracer.withSuspendingSpan("nsjail.destroy_forcibly", ctx = Dispatchers.IO) {
                            process.destroyForcibly()
                        }
                        exitCode = -9
                        processOutput = outputGobbler.await().take(10 * 1024)
                        // Capture child's stderr emitted before kill
                        programStderr = errorGobbler.await().take(10 * 1024)
                        // Read nsjail log file content
                        logContent =
                            tracer.withSuspendingSpan(
                                "nsjail.read_log_on_timeout",
                                ctx = Dispatchers.IO,
                            ) {
                                runCatching { Files.readString(logFilePath) }
                                    .getOrElse { "[Log file unavailable after timeout]" }
                            }
                        return@coroutineScope
                    }

                    exitCode = process.exitValue()
                    processOutput = outputGobbler.await().take(10 * 1024)
                    programStderr = errorGobbler.await().take(10 * 1024)
                    if (programStderr.isNotBlank()) {
                        logger.debug(
                            "Program stderr for job {}: {}",
                            jobId,
                            programStderr.take(500),
                        )
                    }
                    // Read nsjail log from file
                    logContent =
                        tracer.withSuspendingSpan("nsjail.read_log", ctx = Dispatchers.IO) {
                            runCatching { Files.readString(logFilePath) }
                                .getOrElse { "[Failed to read log file: ${it.message}]" }
                        }
                }
            }
        } catch (ioe: IOException) {
            logger.error("IOException during nsjail execution for job {}: {}", jobId, ioe.message)
            return NsjailResult(
                exitCode = -1,
                output = "",
                logContent = "Failed to start nsjail: ${ioe.message}",
                programStderr = "",
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
                programStderr = "",
                timedOut = false,
            )
        }

        return NsjailResult(
            exitCode = exitCode,
            output = processOutput,
            logContent = logContent,
            programStderr = programStderr,
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
        programStderr: String? = null,
        cpuTimeSeconds: Double? = null,
        memoryKb: Long? = null,
        exitCode: Int? = null,
        sandboxLogRef: String? = null,
        errorDetails: String? = null,
    ) {
        withContext(Dispatchers.IO + Context.current().asContextElement()) {
            try {
                val rows =
                    executionJobRepository.updateFinalByIdIfStatus(
                        jobId = UUID.fromString(jobId),
                        expectedStatus = JobStatus.PENDING,
                        status = status,
                        startTime = startTime,
                        endTime = endTime,
                        programOutput = programOutput,
                        programStderr = programStderr,
                        cpuTimeSeconds = cpuTimeSeconds,
                        memoryKb = memoryKb,
                        exitCode = exitCode,
                        sandboxLogRef = sandboxLogRef,
                        errorDetails = errorDetails,
                        workerNodeId = applicationConfig.nodeId,
                    )
                if (rows == 0) {
                    logger.warn(
                        "No rows updated for execution job {}. It may have been already finalized or not found.",
                        jobId,
                    )
                } else {
                    logger.info("Updated execution job {} status to {}", jobId, status)
                }
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
        val programStderr: String,
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
