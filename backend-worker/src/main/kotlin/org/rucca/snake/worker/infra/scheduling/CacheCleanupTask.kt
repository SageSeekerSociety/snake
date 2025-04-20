package org.rucca.snake.worker.infra.scheduling

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes
import java.time.Duration
import java.time.Instant
import kotlin.io.path.exists // Kotlin Path extensions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.rucca.snake.worker.config.ApplicationConfig
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class CacheCleanupTask(private val applicationConfig: ApplicationConfig) {
    private val logger = LoggerFactory.getLogger(CacheCleanupTask::class.java)
    private val cacheBasePath: Path = Paths.get(applicationConfig.cache.basePath)
    private val cacheTtl: Duration = applicationConfig.cache.ttl // Get TTL from config

    // Use a dedicated CoroutineScope for background tasks
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Scheduled task to clean up expired files in the local cache. Runs based on the cron
     * expression defined in the application properties/yaml. Example cron: "0 0 3 * * ?" - Run at 3
     * AM every day
     */
    @Scheduled(cron = "\${application.cache.cleanup-cron:0 0 3 * * ?}") // Default to 3 AM daily
    fun cleanupExpiredCache() {
        if (cacheTtl.isZero || cacheTtl.isNegative) {
            logger.info("Cache cleanup TTL is disabled (ttl <= 0). Skipping cleanup.")
            return
        }

        logger.info(
            "Starting scheduled cache cleanup task for path: {} with TTL: {} days",
            cacheBasePath,
            cacheTtl,
        )
        val cutoffTime = Instant.now().minus(cacheTtl)
        var deletedFilesCount = 0L
        var deletedDirsCount = 0L

        // Launch the cleanup logic in a coroutine to avoid blocking the scheduler thread for long
        // IO operations
        scope.launch {
            try {
                if (!cacheBasePath.exists()) {
                    logger.warn(
                        "Cache base path {} does not exist. Cannot perform cleanup.",
                        cacheBasePath,
                    )
                    return@launch
                }

                // Walk through the user directories (first level under cache base path)
                withContext(Dispatchers.IO) {
                    Files.list(cacheBasePath).use { userDirs -> // Use Files.list for first level
                        userDirs
                            .filter { Files.isDirectory(it) }
                            .forEach { userDir ->
                                // Walk files within each user directory
                                try {
                                    var userFilesDeleted = 0L
                                    var userDirEmpty = true // Assume directory might become empty

                                    Files.walk(userDir).use { paths ->
                                        // Process files first when deleting recursively later
                                        // (implicitly handled by walk)
                                        paths
                                            .filter {
                                                Files.isRegularFile(it)
                                            } // Process only files first
                                            .forEach { file ->
                                                try {
                                                    val attrs =
                                                        Files.readAttributes(
                                                            file,
                                                            BasicFileAttributes::class.java,
                                                        )
                                                    // Use lastModifiedTime as the indicator for TTL
                                                    // check
                                                    val lastModifiedTime =
                                                        attrs.lastModifiedTime().toInstant()

                                                    if (lastModifiedTime.isBefore(cutoffTime)) {
                                                        if (Files.deleteIfExists(file)) {
                                                            logger.debug(
                                                                "Deleted expired cache file: {}",
                                                                file,
                                                            )
                                                            deletedFilesCount++
                                                            userFilesDeleted++
                                                        } else {
                                                            logger.warn(
                                                                "Could not delete expired cache file: {}",
                                                                file,
                                                            )
                                                            userDirEmpty =
                                                                false // If deletion failed, dir is
                                                            // not empty
                                                        }
                                                    } else {
                                                        userDirEmpty =
                                                            false // Found a non-expired file
                                                    }
                                                } catch (e: Exception) {
                                                    logger.error(
                                                        "Error processing cache file {}: {}",
                                                        file,
                                                        e.message,
                                                    )
                                                    userDirEmpty =
                                                        false // Error occurred, assume dir not
                                                    // empty
                                                }
                                            }
                                    } // End Files.walk for files

                                    // After processing files, check if the user directory itself
                                    // might be empty AND old
                                    // (Optional: Delete empty user directories that haven't been
                                    // touched recently)
                                    // For simplicity, we might only delete the directory if *all*
                                    // its files were deleted in this run.
                                    // A more robust check would involve checking the directory's
                                    // last modified time.
                                    // Let's keep it simple: if we deleted files AND the directory
                                    // is now empty, delete it.
                                    // However, deleting based only on file deletion could remove a
                                    // dir that was just created
                                    // but had old files copied in. A safer approach is to only
                                    // delete files.

                                    // Let's refine: Delete empty directories whose *own* last
                                    // modified time is old.
                                    val dirAttrs =
                                        Files.readAttributes(
                                            userDir,
                                            BasicFileAttributes::class.java,
                                        )
                                    val dirLastModified = dirAttrs.lastModifiedTime().toInstant()
                                    val isDirEmpty =
                                        Files.list(userDir).use {
                                            it.findFirst().isEmpty
                                        } // Check if directory is empty NOW

                                    if (isDirEmpty && dirLastModified.isBefore(cutoffTime)) {
                                        try {
                                            if (Files.deleteIfExists(userDir)) {
                                                logger.info(
                                                    "Deleted empty and expired cache directory: {}",
                                                    userDir,
                                                )
                                                deletedDirsCount++
                                            } else {
                                                logger.warn(
                                                    "Could not delete apparently empty and expired directory: {}",
                                                    userDir,
                                                )
                                            }
                                        } catch (e: Exception) {
                                            logger.error(
                                                "Error deleting directory {}: {}",
                                                userDir,
                                                e.message,
                                            )
                                        }
                                    }
                                } catch (e: Exception) {
                                    logger.error(
                                        "Error processing user cache directory {}: {}",
                                        userDir,
                                        e.message,
                                    )
                                }
                            } // End forEach userDir
                    } // End Files.list for userDirs
                } // End withContext(Dispatchers.IO)

                logger.info(
                    "Cache cleanup task finished. Deleted {} files and {} directories.",
                    deletedFilesCount,
                    deletedDirsCount,
                )
            } catch (e: Exception) {
                logger.error("Unhandled exception during cache cleanup task: ${e.message}", e)
            }
        } // End scope.launch
    }
}
