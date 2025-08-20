package org.rucca.snake.worker.domain.service

import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.instrumentation.annotations.WithSpan
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.rucca.snake.worker.config.ApplicationConfig
import org.rucca.snake.worker.infra.storage.MinioObjectInfo
import org.rucca.snake.worker.infra.storage.MinioService
import org.rucca.snake.worker.utils.withSuspendingSpan
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class CacheManager(
    private val minioService: MinioService,
    applicationConfig: ApplicationConfig,
    openTelemetry: OpenTelemetry,
) {
    private val tracer = openTelemetry.getTracer(CacheManager::class.java.name)
    private val logger = LoggerFactory.getLogger(CacheManager::class.java)
    private val cacheBasePath: Path = Paths.get(applicationConfig.cache.basePath)

    // Mutex map to prevent concurrent downloads/updates for the same object key
    // Key: MinIO object key, Value: Mutex
    private val objectLocks = ConcurrentHashMap<String, Mutex>()

    init {
        // Ensure cache base directory exists on startup
        try {
            Files.createDirectories(cacheBasePath)
            logger.info("Local cache directory initialized at: {}", cacheBasePath.toAbsolutePath())
        } catch (e: Exception) {
            logger.error(
                "Failed to create cache base directory at {}: {}",
                cacheBasePath,
                e.message,
                e,
            )
            throw IllegalStateException("Failed to initialize cache directory", e)
        }
    }

    /**
     * Gets the local path to the program binary, managing cache and downloads. This version uses
     * Last-Modified time for basic cache validation. Includes locking to prevent race conditions
     * during concurrent requests for the same file.
     *
     * @param userId The ID of the user (used for structuring cache path).
     * @param objectKey The unique key of the object in MinIO (e.g.,
     *   "programs/{userId}/program-v1").
     * @return The Path to the locally cached program file, or null if it cannot be obtained.
     */
    @WithSpan("cache.get_program_path")
    suspend fun getProgramPath(userId: Long, objectKey: String): Path? {
        // Construct local cache path based on objectKey structure or userId
        // Using a structure reflecting the object key might be better if keys include versions
        // Example: If objectKey is "programs/123/program-v2", cache path could be
        // cache/programs/123/program-v2
        // For simplicity, let's stick to userId/program for now, assuming objectKey format
        // consistency
        val userCacheDir = cacheBasePath.resolve(userId.toString())
        val localPath = userCacheDir.resolve("program") // Assuming a standard name in cache

        // Get or create a Mutex for this specific object key to handle concurrency
        val lock = objectLocks.computeIfAbsent(objectKey) { Mutex() }

        // Acquire the lock for this object key. Only one coroutine can proceed at a time for the
        // same key.
        lock.withLock {
            try {
                // 1. Get latest metadata from MinIO (this tells us if the object exists and its
                // last modified time)
                val remoteInfo: MinioObjectInfo? = minioService.statObject(objectKey)
                if (remoteInfo == null) {
                    logger.warn(
                        "Object '{}' not found in MinIO for user {}. Cannot provide path.",
                        objectKey,
                        userId,
                    )
                    // Ensure local cache (if exists from a previous version) is removed
                    withContext(Dispatchers.IO) { Files.deleteIfExists(localPath) }
                    return null
                }

                // 2. Check local cache validity
                var needsDownload = true
                if (Files.exists(localPath)) {
                    val localLastModified: Instant =
                        withContext(Dispatchers.IO) {
                            Files.getLastModifiedTime(localPath).toInstant()
                        }
                    val remoteLastModified: Instant? = remoteInfo.lastModified?.toInstant()

                    // Compare LastModified times for validation
                    if (remoteLastModified != null && localLastModified == remoteLastModified) {
                        logger.debug(
                            "Cache hit and validated via LastModified for object '{}', user {}.",
                            objectKey,
                            userId,
                        )
                        needsDownload = false
                    } else {
                        logger.info(
                            "Local cache for object '{}' exists but is outdated or remote time unavailable. Needs download.",
                            objectKey,
                        )
                        // Optionally log details: Local: $localLastModified, Remote:
                        // $remoteLastModified
                    }
                    // Consider adding ETag comparison here if needed for stronger validation
                    // Requires storing the ETag locally alongside the file, e.g., in a ".etag"
                    // file.
                } else {
                    logger.info(
                        "Local cache miss for object '{}', user {}. Needs download.",
                        objectKey,
                        userId,
                    )
                }

                // 3. Download if necessary
                if (needsDownload) {
                    logger.info(
                        "Downloading object '{}' for user {} to '{}'.",
                        objectKey,
                        userId,
                        localPath,
                    )
                    // Ensure parent directory exists within the locked section
                    withContext(Dispatchers.IO) { Files.createDirectories(localPath.parent) }

                    val downloadSuccess = tracer.withSuspendingSpan("cache.download_on_miss") { minioService.downloadObject(objectKey, localPath) }
                    if (!downloadSuccess) {
                        logger.error(
                            "Failed to download object '{}' for user {}.",
                            objectKey,
                            userId,
                        )
                        return null // Download failed
                    }

                    // After successful download, set the local file's last modified time
                    // to match the remote object's time for future validation.
                    remoteInfo.lastModified?.let { remoteModTime ->
                        try {
                            withContext(Dispatchers.IO) {
                                Files.setLastModifiedTime(
                                    localPath,
                                    FileTime.from(remoteModTime.toInstant()),
                                )
                                logger.debug(
                                    "Set local file mod time for '{}' to match remote: {}",
                                    objectKey,
                                    remoteModTime,
                                )
                            }
                        } catch (e: Exception) {
                            logger.warn(
                                "Could not set last modified time on downloaded file '{}': {}",
                                localPath,
                                e.message,
                            )
                            // This is not fatal, but might cause unnecessary re-downloads later
                        }
                    }
                    // Optional: Store ETag if using ETag validation
                    // remoteInfo.etag?.let { writeLocalEtag(localPath, it) }
                }

                // 4. Update "Access Time" for TTL mechanism (using Last Modified as proxy)
                // Since the TTL cleanup task checks Last Modified, we update it here on access.
                try {
                    withContext(Dispatchers.IO) {
                        Files.setLastModifiedTime(localPath, FileTime.from(Instant.now()))
                        logger.debug(
                            "Updated effective access time (via LastModified) for cached file: {}",
                            localPath,
                        )
                    }
                } catch (e: Exception) {
                    // Log warning but don't fail if access time update fails
                    logger.warn(
                        "Failed to update access time on cached file '{}': {}",
                        localPath,
                        e.message,
                    )
                }

                return localPath // Return the path to the valid local file
            } catch (e: Exception) {
                // Catch exceptions during the locked operation
                logger.error(
                    "Exception in CacheManager.getProgramPath for object '{}', user {}: {}",
                    objectKey,
                    userId,
                    e.message,
                    e,
                )
                // Clean up potentially inconsistent local state if an error occurred during
                // download/update
                try {
                    withContext(Dispatchers.IO + NonCancellable) { Files.deleteIfExists(localPath) }
                } catch (_: Exception) {}
                return null
            }
            // The lock is automatically released when exiting the withLock block
        }
    }

    /**
     * Invalidates (deletes) the local cache for a specific user. Should be made safe for concurrent
     * calls if necessary, though typically called sequentially after compilation.
     *
     * @param userId The ID of the user whose cache should be invalidated.
     */
    suspend fun invalidateCache(userId: Long) {
        val userCacheDir = cacheBasePath.resolve(userId.toString())
        // Optional: Use a lock specific to the user directory if concurrent invalidation is
        // possible and problematic
        // val lock = objectLocks.computeIfAbsent("invalidate_${userId}") { Mutex() }
        // lock.withLock { ... }

        withContext(Dispatchers.IO) {
            try {
                if (Files.exists(userCacheDir)) {
                    Files.walk(userCacheDir)
                        .sorted(
                            Comparator.reverseOrder()
                        ) // Important: delete contents before directory
                        .forEach { path ->
                            try {
                                Files.deleteIfExists(path)
                            } catch (e: IOException) {
                                logger.warn(
                                    "Failed to delete path during cache invalidation {}: {}",
                                    path,
                                    e.message,
                                )
                            }
                        }
                    logger.info(
                        "Invalidated local cache directory for user {}: {}",
                        userId,
                        userCacheDir,
                    )
                }
            } catch (e: Exception) {
                logger.error(
                    "Error during cache invalidation for user {}: {}",
                    userId,
                    e.message,
                    e,
                )
                // Decide if this error needs further action
            }
        }
    }

    // --- Helper methods for ETag validation (Optional) ---
    /*
    private suspend fun readLocalEtag(programPath: Path): String? {
        val etagFile = programPath.resolveSibling(programPath.fileName.toString() + ".etag")
        return try {
            withContext(Dispatchers.IO) {
                if (Files.exists(etagFile)) Files.readString(etagFile).trim() else null
            }
        } catch (e: IOException) {
            logger.warn("Could not read etag file for {}: {}", programPath, e.message)
            null
        }
    }

    private suspend fun writeLocalEtag(programPath: Path, etag: String?) {
        if (etag == null) return
        val etagFile = programPath.resolveSibling(programPath.fileName.toString() + ".etag")
        try {
            withContext(Dispatchers.IO) {
                Files.writeString(etagFile, etag)
            }
        } catch (e: IOException) {
            logger.warn("Could not write etag file for {}: {}", programPath, e.message)
        }
    }
    */
}
