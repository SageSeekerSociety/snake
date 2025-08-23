package org.rucca.snake.worker.domain.service

// KeyedMutex removed in favor of request coalescing via Deferreds
import com.github.benmanes.caffeine.cache.Caffeine
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.trace.Span
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import org.rucca.snake.common.utils.withSuspendingSpan
import org.rucca.snake.worker.config.ApplicationConfig
import org.rucca.snake.worker.infra.storage.MinioObjectInfo
import org.rucca.snake.worker.infra.storage.MinioService
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service

@Service
class CacheManager(
    private val minioService: MinioService,
    private val applicationConfig: ApplicationConfig,
    openTelemetry: OpenTelemetry,
    private val meterRegistry: MeterRegistry,
) {
    // ---- Tracing / Logging ----
    private val tracer = openTelemetry.getTracer(CacheManager::class.java.name)
    private val logger = LoggerFactory.getLogger(CacheManager::class.java)

    // ---- Paths & Config ----
    private val cacheBasePath: Path = Paths.get(applicationConfig.cache.basePath)
    private val staleTolerance: Duration = applicationConfig.cache.staleTolerance
    private val atimeTouchWindowSec: Long = applicationConfig.cache.atimeTouchWindowSec

    // ---- Request coalescing: track in-flight downloads keyed by user|objectKey ----
    private val inFlightDownloads = ConcurrentHashMap<String, CompletableDeferred<Path?>>()

    // ---- Metadata cache to reduce remote stat pressure ----
    private val metadataCache =
        Caffeine.newBuilder()
            .expireAfterWrite(5, TimeUnit.MINUTES) // balance freshness vs. stat pressure
            .maximumSize(100_000)
            .build<String, MinioObjectInfo>()

    // ---- In-memory "recent touch" cache to throttle atime writes ----
    private val recentTouch =
        Caffeine.newBuilder()
            .expireAfterWrite(5, TimeUnit.MINUTES)
            .maximumSize(50_000)
            .build<Path, Long>() // value = last touch epoch seconds

    // ---- Cleanup scope ----
    private val cacheCleanScope =
        CoroutineScope(Dispatchers.IO + SupervisorJob() + CoroutineName("CacheCleanupScope"))

    private val cleanupRunning = AtomicBoolean(false)

    // ---- Micrometer metrics (low-cardinality) ----
    private val getTimer: Timer =
        Timer.builder("cache.program.get.latency")
            .publishPercentiles(0.5, 0.9, 0.99)
            .register(meterRegistry)
    private val lockWaitTimer: Timer = Timer.builder("cache.lock.wait").register(meterRegistry)
    private val statTimer: Timer =
        Timer.builder("cache.remote.stat.latency").register(meterRegistry)
    private val downloadTimer: Timer =
        Timer.builder("cache.download.latency").register(meterRegistry)
    private val cleanupTimer: Timer =
        Timer.builder("cache.cleanup.duration").register(meterRegistry)

    private val outcomes =
        listOf(
            "hit",
            "hit_double",
            "hit_after_stat",
            "miss_downloaded",
            "miss_not_found",
            "fallback_stale_ok",
            "fallback_download_failed",
            "coalesced",
            "error",
        )
    private val outcomeCounters =
        outcomes.associateWith { oc ->
            Counter.builder("cache.program.requests").tag("outcome", oc).register(meterRegistry)
        }

    private fun incOutcome(outcome: String) {
        outcomeCounters[outcome]?.increment()
    }

    private val cleanupDeleted = Counter.builder("cache.cleanup.deleted").register(meterRegistry)
    private val cleanupScanned = Counter.builder("cache.cleanup.scanned").register(meterRegistry)

    init {
        try {
            Files.createDirectories(cacheBasePath)
            logger.info("Local cache directory initialized at: {}", cacheBasePath.toAbsolutePath())
        } catch (e: Exception) {
            logger.error("Failed to create cache base directory at $cacheBasePath", e)
            throw IllegalStateException("Failed to initialize cache directory", e)
        }

        // Useful internal gauges
        io.micrometer.core.instrument.Gauge.builder("cache.metadata_cache.size") {
                metadataCache.estimatedSize().toDouble()
            }
            .register(meterRegistry)
    }

    fun invalidateMetadata(objectKey: String) {
        metadataCache.invalidate(objectKey)
        logger.info("Invalidated metadata cache for objectKey={}", objectKey)
    }

    suspend fun getProgramPath(userId: Long, objectKey: String): Path? =
        tracer.withSuspendingSpan("cache.get_program_path", ctx = Dispatchers.IO) {
            val timerSample = Timer.start(meterRegistry)
            try {
                // annotate method span with identifiers useful for debugging
                Span.current().apply {
                    setAttribute("user.id", userId)
                    setAttribute("object.key", objectKey)
                }
                val localPath = getLocalPathForObject(userId, objectKey)

                // 1) Try metadata cache + local validity (no locks)
                metadataCache.getIfPresent(objectKey)?.let { info ->
                    if (isLocalFileValid(localPath, info)) {
                        maybeTouchAtime(localPath)
                        incOutcome("hit")
                        Span.current().setAttribute("cache.outcome", "hit")
                        timerSample.stop(getTimer)
                        return@withSuspendingSpan localPath
                    }
                }

                val key = "$userId|$objectKey"

                // If a download is already in-flight, coalesce and wait
                inFlightDownloads[key]?.let { existing ->
                    val waitSample = Timer.start(meterRegistry)
                    try {
                        val res =
                            tracer.withSuspendingSpan("cache.download.await") { existing.await() }
                        incOutcome("coalesced")
                        Span.current().setAttribute("cache.outcome", "coalesced")
                        timerSample.stop(getTimer)
                        return@withSuspendingSpan res
                    } finally {
                        waitSample.stop(lockWaitTimer)
                    }
                }

                // Try to become the leader
                val promise = CompletableDeferred<Path?>()
                val prev = inFlightDownloads.putIfAbsent(key, promise)
                if (prev != null) {
                    val waitSample = Timer.start(meterRegistry)
                    try {
                        val res = tracer.withSuspendingSpan("cache.download.await") { prev.await() }
                        incOutcome("coalesced")
                        Span.current().setAttribute("cache.outcome", "coalesced")
                        timerSample.stop(getTimer)
                        return@withSuspendingSpan res
                    } finally {
                        waitSample.stop(lockWaitTimer)
                    }
                }

                // Leader path: execute metadata fetch + optional download under a span
                var leaderResult: Path? = null
                try {
                    leaderResult =
                        tracer.withSuspendingSpan("cache.download.execute") {
                            // Double-check after acquiring leadership
                            metadataCache.getIfPresent(objectKey)?.let { info2 ->
                                if (isLocalFileValid(localPath, info2)) {
                                    maybeTouchAtime(localPath)
                                    incOutcome("hit_double")
                                    Span.current().setAttribute("cache.outcome", "hit_double")
                                    timerSample.stop(getTimer)
                                    return@withSuspendingSpan localPath
                                }
                            }

                            // Refresh remote metadata
                            val remoteInfo =
                                try {
                                    val statSample = Timer.start(meterRegistry)
                                    try {
                                        tracer.withSuspendingSpan("minio.stat_object") {
                                            minioService.statObject(objectKey)
                                        }
                                    } finally {
                                        statSample.stop(statTimer)
                                    }
                                } catch (ex: Exception) {
                                    // Remote issue: optional stale tolerance fallback
                                    if (Files.exists(localPath) && staleTolerance > Duration.ZERO) {
                                        val lm = safeGetLastModified(localPath)
                                        if (
                                            lm != null &&
                                                lm.isAfter(Instant.now().minus(staleTolerance))
                                        ) {
                                            logger.warn(
                                                "Remote stat failed; using locally cached '{}' within stale tolerance.",
                                                objectKey,
                                            )
                                            incOutcome("fallback_stale_ok")
                                            Span.current()
                                                .setAttribute("cache.outcome", "fallback_stale_ok")
                                            maybeTouchAtime(localPath)
                                            timerSample.stop(getTimer)
                                            return@withSuspendingSpan localPath
                                        }
                                    }
                                    logger.warn(
                                        "Remote stat failed for '{}': {}",
                                        objectKey,
                                        ex.message,
                                    )
                                    incOutcome("error")
                                    Span.current().setAttribute("cache.outcome", "error")
                                    timerSample.stop(getTimer)
                                    return@withSuspendingSpan null
                                }

                            if (remoteInfo == null) {
                                // Remote 404: drop any local copy
                                withContext(Dispatchers.IO) { Files.deleteIfExists(localPath) }
                                metadataCache.invalidate(objectKey)
                                incOutcome("miss_not_found")
                                Span.current().setAttribute("cache.outcome", "miss_not_found")
                                Span.current().setAttribute("cache.remote.not_found", true)
                                timerSample.stop(getTimer)
                                return@withSuspendingSpan null
                            }

                            metadataCache.put(objectKey, remoteInfo)

                            // Download on miss or invalid local
                            if (!isLocalFileValid(localPath, remoteInfo)) {
                                val dlSample = Timer.start(meterRegistry)
                                val ok =
                                    try {
                                        downloadAndPlaceAtomically(localPath, remoteInfo)
                                    } catch (ex: Exception) {
                                        logger.error(
                                            "Download error for '{}': {}",
                                            objectKey,
                                            ex.message,
                                        )
                                        false
                                    } finally {
                                        dlSample.stop(downloadTimer)
                                    }
                                if (!ok) {
                                    if (Files.exists(localPath) && staleTolerance > Duration.ZERO) {
                                        val lm = safeGetLastModified(localPath)
                                        if (
                                            lm != null &&
                                                lm.isAfter(Instant.now().minus(staleTolerance))
                                        ) {
                                            incOutcome("fallback_download_failed")
                                            Span.current()
                                                .setAttribute(
                                                    "cache.outcome",
                                                    "fallback_download_failed",
                                                )
                                            maybeTouchAtime(localPath)
                                            timerSample.stop(getTimer)
                                            return@withSuspendingSpan localPath
                                        }
                                    }
                                    incOutcome("error")
                                    Span.current().setAttribute("cache.outcome", "error")
                                    timerSample.stop(getTimer)
                                    return@withSuspendingSpan null
                                }
                                incOutcome("miss_downloaded")
                                Span.current().setAttribute("cache.outcome", "miss_downloaded")
                            } else {
                                incOutcome("hit_after_stat")
                                Span.current().setAttribute("cache.outcome", "hit_after_stat")
                            }

                            maybeTouchAtime(localPath)
                            timerSample.stop(getTimer)
                            return@withSuspendingSpan localPath
                        }
                } catch (t: Throwable) {
                    promise.completeExceptionally(t)
                    throw t
                } finally {
                    inFlightDownloads.remove(key, promise)
                }
                // Complete and return based on leader outcome
                promise.complete(leaderResult)
                return@withSuspendingSpan leaderResult
            } catch (t: Throwable) {
                incOutcome("error")
                timerSample.stop(getTimer)
                throw t
            }
        }

    // ---------- Helpers ----------

    /** Safe atime touch with write-throttling to reduce inode churn under high QPS. */
    private suspend fun maybeTouchAtime(pathRaw: Path) {
        val path = pathRaw.toAbsolutePath().normalize()
        val nowSec = System.currentTimeMillis() / 1000
        val last = recentTouch.getIfPresent(path)
        if (last == null || nowSec - last >= atimeTouchWindowSec) {
            recentTouch.put(path, nowSec)
            withContext(Dispatchers.IO) {
                try {
                    val nowFt = FileTime.from(Instant.now())
                    Files.setAttribute(path, "basic:lastAccessTime", nowFt)
                    val side = etagSidecarPath(path)
                    if (Files.exists(side)) {
                        Files.setAttribute(side, "basic:lastAccessTime", nowFt)
                    }
                } catch (_: UnsupportedOperationException) {
                    logger.warnOnce(
                        "Filesystem does not support updating last access time for '{}'. Cleanup TTL may be less accurate.",
                        path,
                    )
                } catch (e: Exception) {
                    logger.warn("Failed to update access time for '{}': {}", path, e.message)
                }
            }
        }
    }

    private fun getLocalPathForObject(userId: Long, objectKey: String): Path {
        val userCacheDir = cacheBasePath.resolve(userId.toString())
        val objectKeyHash = hashObjectKey(objectKey)
        val fileName = Paths.get(objectKey).fileName.toString()
        return userCacheDir.resolve(objectKeyHash).resolve(fileName)
    }

    /**
     * Local validity check: prefer strong validators (ETag/size), fallback to Last-Modified with
     * tolerance.
     */
    private suspend fun isLocalFileValid(localPath: Path, remote: MinioObjectInfo): Boolean =
        withContext(Dispatchers.IO) {
            if (!Files.exists(localPath)) return@withContext false
            try {
                // 1) Strong check via ETag sidecar (if provided by remote)
                val remoteEtag = remoteEtag(remote)
                if (remoteEtag != null) {
                    val localEtag = readEtagSidecar(localPath)
                    if (localEtag == remoteEtag) return@withContext true
                    return@withContext false
                }

                // 2) Fallback: Last-Modified with 1s tolerance
                val localM = Files.getLastModifiedTime(localPath).toInstant()
                val remoteM = remote.lastModified?.toInstant()
                if (remoteM != null) {
                    val diff = kotlin.math.abs(Duration.between(localM, remoteM).seconds)
                    return@withContext diff <= 1
                }
                // 3) If remote has no validators, assume ok if file exists
                true
            } catch (e: IOException) {
                logger.warn("Failed to read local metadata {}: {}", localPath, e.message)
                false
            }
        }

    /** Atomic download: save to temp, write validators, then atomic move into place. */
    private suspend fun downloadAndPlaceAtomically(
        localPath: Path,
        remote: MinioObjectInfo,
    ): Boolean {
        withContext(Dispatchers.IO) { Files.createDirectories(localPath.parent) }
        val tmp =
            withContext(Dispatchers.IO) { Files.createTempFile(localPath.parent, ".dl-", ".part") }
        return try {
            val ok =
                tracer.withSuspendingSpan("cache.download_on_miss") {
                    // annotate download span with remote metadata
                    Span.current().apply {
                        setAttribute("object.key", remote.objectKey)
                        setAttribute("remote.etag", remote.etag ?: "")
                        setAttribute("remote.size_bytes", remote.size)
                    }
                    minioService.downloadObject(remote.objectKey, tmp)
                }
            if (!ok) return false

            remoteEtag(remote)?.let { writeEtagSidecar(tmp, it) }
            remote.lastModified?.let { lm ->
                withContext(Dispatchers.IO) {
                    Files.setLastModifiedTime(tmp, FileTime.from(lm.toInstant()))
                }
            }
            withContext(Dispatchers.IO) {
                Files.move(
                    tmp,
                    localPath,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
            remoteEtag(remote)?.let {
                val tmpSide = etagSidecarPath(tmp)
                val finalSide = etagSidecarPath(localPath)
                withContext(Dispatchers.IO) {
                    if (Files.exists(tmpSide)) {
                        Files.move(
                            tmpSide,
                            finalSide,
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING,
                        )
                    }
                }
            }
            true
        } catch (e: Exception) {
            logger.error("Exception during atomic download '{}': {}", localPath, e.message)
            false
        } finally {
            withContext(Dispatchers.IO + NonCancellable) {
                Files.deleteIfExists(tmp)
                Files.deleteIfExists(etagSidecarPath(tmp))
            }
        }
    }

    private fun hashObjectKey(key: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(key.toByteArray(StandardCharsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun remoteEtag(info: MinioObjectInfo): String? {
        return info.etag
    }

    private fun etagSidecarPath(path: Path): Path = path.resolveSibling("${path.fileName}.etag")

    private fun readEtagSidecar(path: Path): String? =
        try {
            val p = etagSidecarPath(path)
            if (!Files.exists(p)) null else Files.readString(p).trim().ifBlank { null }
        } catch (_: Exception) {
            null
        }

    private fun writeEtagSidecar(path: Path, etag: String) {
        runCatching {
            val p = etagSidecarPath(path)
            Files.writeString(p, etag)
        }
    }

    private fun safeGetLastModified(path: Path): Instant? =
        try {
            Files.getLastModifiedTime(path).toInstant()
        } catch (_: Exception) {
            null
        }

    /**
     * Scheduled disk cleanup: remove files not accessed since cutoff. Uses throttled atime updates;
     * falls back silently if FS doesn't support atime.
     */
    @Scheduled(cron = "\${application.cache.cleanup-cron:0 0 3 * * ?}")
    fun cleanUpExpiredCache() {
        if (!cleanupRunning.compareAndSet(false, true)) return
        val ttl = applicationConfig.cache.ttl
        if (ttl.isZero || ttl.isNegative) {
            logger.info("Cache TTL is disabled. Skipping scheduled disk cleanup.")
            return
        }
        val cutoff = Instant.now().minus(ttl)
        logger.info(
            "Running scheduled cache disk cleanup. Removing files not accessed since: {}",
            cutoff,
        )

        cacheCleanScope.launch {
            cleanupTimer.record<Unit> {
                var deletedCount = 0L
                var scannedCount = 0L
                try {
                    if (!Files.exists(cacheBasePath)) return@record

                    Files.walk(cacheBasePath).use { paths ->
                        paths
                            .filter { Files.isRegularFile(it) }
                            .forEach { path ->
                                scannedCount++
                                try {
                                    val attrs =
                                        Files.readAttributes(path, BasicFileAttributes::class.java)
                                    val lastAccessTime = attrs.lastAccessTime().toInstant()
                                    val name = path.fileName.toString()
                                    if (lastAccessTime.isBefore(cutoff) || name.endsWith(".part")) {
                                        Files.deleteIfExists(path)
                                        val side = etagSidecarPath(path)
                                        Files.deleteIfExists(side)
                                        removeEmptyParents(path.parent, cacheBasePath)
                                        deletedCount++
                                    }
                                } catch (e: Exception) {
                                    logger.warn(
                                        "Failed to process/delete cache file {}: {}",
                                        path,
                                        e.message,
                                    )
                                }
                            }
                    }
                } catch (e: Exception) {
                    logger.error("Error during scheduled cache disk cleanup task", e)
                } finally {
                    cleanupRunning.set(false)
                    cleanupScanned.increment(scannedCount.toDouble())
                    cleanupDeleted.increment(deletedCount.toDouble())
                    if (deletedCount > 0) {
                        logger.info(
                            "Cache disk cleanup finished. Deleted {} expired files.",
                            deletedCount,
                        )
                    } else {
                        logger.info(
                            "Cache disk cleanup finished. No expired files found to delete."
                        )
                    }
                }
            }
        }
    }

    private fun removeEmptyParents(start: Path?, stopAt: Path) {
        var p = start ?: return
        while (p != stopAt && p.startsWith(stopAt)) {
            try {
                if (Files.isDirectory(p) && Files.list(p).use { it.findAny().isEmpty }) {
                    Files.deleteIfExists(p)
                    p = p.parent ?: break
                } else {
                    break
                }
            } catch (_: Exception) {
                break
            }
        }
    }

    // ---- Log-once utility to avoid noisy warnings ----
    private val alreadyLogged = ConcurrentHashMap.newKeySet<String>()

    private fun Logger.warnOnce(message: String, vararg args: Any?) {
        if (alreadyLogged.add(message)) {
            this.warn(message, *args)
        }
    }
}
