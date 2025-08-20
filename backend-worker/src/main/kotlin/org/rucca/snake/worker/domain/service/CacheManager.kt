package org.rucca.snake.worker.domain.service

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.rucca.snake.worker.config.ApplicationConfig
import org.rucca.snake.worker.infra.storage.MinioObjectInfo
import org.rucca.snake.worker.infra.storage.MinioService
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service

@Service
class CacheManager(
    private val minioService: MinioService,
    private val applicationConfig: ApplicationConfig,
) {
    private val cacheCleanScope =
        CoroutineScope(Dispatchers.IO + NonCancellable + CoroutineName("CacheCleanupScope"))
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
            logger.error("Failed to create cache base directory at $cacheBasePath", e)
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
    suspend fun getProgramPath(userId: Long, objectKey: String): Path? {
        val userCacheDir = cacheBasePath.resolve(userId.toString())
        val objectKeyHash = hashObjectKey(objectKey)
        val localPath =
            userCacheDir.resolve(objectKeyHash).resolve(Paths.get(objectKey).fileName.toString())

        val lock = objectLocks.computeIfAbsent(objectKey) { Mutex() }

        lock.withLock {
            try {
                // 1. Get latest metadata from MinIO
                val remoteInfo: MinioObjectInfo? = minioService.statObject(objectKey)
                if (remoteInfo == null) {
                    logger.warn(
                        "Object '{}' not found in MinIO for user {}. Cannot provide path.",
                        objectKey,
                        userId,
                    )
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

                    if (remoteLastModified != null && localLastModified == remoteLastModified) {
                        logger.debug(
                            "Cache hit and validated via LastModified for object '{}', user {}.",
                            objectKey,
                            userId,
                        )
                        needsDownload = false
                    } else {
                        logger.info(
                            "Local cache for object '{}' exists but is outdated or remote time unavailable. Needs download. Local: {}, Remote: {}",
                            objectKey,
                            localLastModified,
                            remoteLastModified,
                        )
                    }
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
                    withContext(Dispatchers.IO) { Files.createDirectories(localPath.parent) }

                    val downloadSuccess = minioService.downloadObject(objectKey, localPath)
                    if (!downloadSuccess) {
                        logger.error(
                            "Failed to download object '{}' for user {}.",
                            objectKey,
                            userId,
                        )
                        return null
                    }

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
                        }
                    }
                }

                // 4. Update real "Access Time" for TTL mechanism
                try {
                    withContext(Dispatchers.IO) {
                        Files.setAttribute(
                            localPath,
                            "basic:lastAccessTime",
                            FileTime.from(Instant.now()),
                        )
                        logger.debug("Updated last access time for cached file: {}", localPath)
                    }
                } catch (e: UnsupportedOperationException) {
                    logger.warn(
                        "Filesystem does not support updating last access time for '{}'. TTL cleanup might not work as expected.",
                        localPath,
                    )
                } catch (e: Exception) {
                    logger.warn(
                        "Failed to update access time on cached file '{}': {}",
                        localPath,
                        e.message,
                    )
                }

                return localPath
            } catch (e: Exception) {
                logger.error(
                    "Exception in CacheManager.getProgramPath for object '$objectKey', user $userId",
                    e,
                )
                try {
                    withContext(Dispatchers.IO + NonCancellable) { Files.deleteIfExists(localPath) }
                } catch (_: Exception) {}
                return null
            }
        }
    }

    private fun hashObjectKey(key: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(key.toByteArray(StandardCharsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    @Scheduled(fixedRate = 3600_000)
    fun cleanUpExpiredCache() {
        val ttl = applicationConfig.cache.ttl
        if (ttl.isZero || ttl.isNegative) {
            logger.info("Cache TTL is disabled. Skipping cleanup.")
            return
        }

        val cutoff = Instant.now().minus(ttl)
        logger.info(
            "Running scheduled cache cleanup. Removing files not accessed since: {}",
            cutoff,
        )

        try {
            // 使用 a coroutine for non-blocking IO
            // 注意：这里没有包含在原始代码里，需要添加 kotlinx-coroutines-core 依赖
            // GlobalScope is used for simplicity, but a custom scope is better practice.
            cacheCleanScope.launch {
                var deletedCount = 0L
                Files.walk(cacheBasePath).use { paths ->
                    paths
                        .filter { Files.isRegularFile(it) }
                        .forEach { path ->
                            try {
                                val attrs =
                                    Files.readAttributes(path, BasicFileAttributes::class.java)
                                val lastAccessTime = attrs.lastAccessTime().toInstant()

                                if (lastAccessTime.isBefore(cutoff)) {
                                    Files.delete(path)
                                    deletedCount++
                                    logger.info("Deleted expired cache file: {}", path)
                                }
                            } catch (e: Exception) {
                                logger.warn(
                                    "Failed to process or delete cache file {}: {}",
                                    path,
                                    e.message,
                                )
                            }
                        }
                }
                if (deletedCount > 0) {
                    logger.info("Cache cleanup finished. Deleted {} expired files.", deletedCount)
                } else {
                    logger.info("Cache cleanup finished. No expired files found.")
                }
            }
        } catch (e: Exception) {
            logger.error("Error during cache cleanup task", e)
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
