package org.rucca.snake.controller.infra.storage

import io.minio.*
import io.minio.errors.ErrorResponseException
import io.minio.errors.MinioException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.ZonedDateTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

// Simple data class to hold object metadata we might need
data class MinioObjectInfo(
    val objectKey: String,
    val etag: String?,
    val lastModified: ZonedDateTime?,
    val size: Long,
)

@Service
class MinioService(private val minioClient: MinioClient) {
    private val logger = LoggerFactory.getLogger(MinioService::class.java)

    @Value("\${minio.bucketName}") private lateinit var bucketName: String

    /**
     * Ensures the bucket exists, creates it if not. Should be called once on application startup if
     * needed.
     */
    suspend fun ensureBucketExists() {
        withContext(Dispatchers.IO) {
            try {
                val found =
                    minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucketName).build())
                if (!found) {
                    minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucketName).build())
                    logger.info("Bucket '{}' created successfully.", bucketName)
                } else {
                    logger.info("Bucket '{}' already exists.", bucketName)
                }
            } catch (e: MinioException) {
                logger.error("Error checking or creating bucket '{}': {}", bucketName, e.message, e)
                // Depending on the error, you might want to throw or handle differently
                throw IllegalStateException("Failed to ensure MinIO bucket exists", e)
            }
        }
    }

    /**
     * Uploads a file from a local path to MinIO.
     *
     * @param objectKey The key (path) under which to store the object in the bucket.
     * @param filePath The local path of the file to upload.
     * @param contentType Optional content type (e.g., "application/octet-stream").
     * @return The ETag of the uploaded object, or null on failure.
     */
    suspend fun uploadFile(
        objectKey: String,
        filePath: Path,
        contentType: String = "application/octet-stream",
    ): String? {
        return withContext(Dispatchers.IO) {
            try {
                val args =
                    UploadObjectArgs.builder()
                        .bucket(bucketName)
                        .`object`(objectKey)
                        .filename(filePath.toString())
                        .contentType(contentType)
                        .build()
                val response = minioClient.uploadObject(args)
                logger.info(
                    "Successfully uploaded '{}' to MinIO as '{}'. ETag: {}",
                    filePath,
                    objectKey,
                    response.etag(),
                )
                response.etag() // Return ETag on success
            } catch (e: Exception) { // Catch more general exceptions for upload
                logger.error(
                    "Error uploading file '{}' to '{}': {}",
                    filePath,
                    objectKey,
                    e.message,
                    e,
                )
                null // Return null on failure
            }
        }
    }

    /**
     * Downloads an object from MinIO to a local file path. Overwrites the local file if it exists.
     *
     * @param objectKey The key of the object to download.
     * @param destinationPath The local path where the object should be saved.
     * @return True if download was successful, false otherwise.
     */
    suspend fun downloadObject(objectKey: String, destinationPath: Path): Boolean {
        // Ensure parent directories exist
        withContext(Dispatchers.IO) { Files.createDirectories(destinationPath.parent) }

        return withContext(Dispatchers.IO) {
            var inputStream: InputStream? = null
            try {
                val args = GetObjectArgs.builder().bucket(bucketName).`object`(objectKey).build()
                inputStream = minioClient.getObject(args)

                // Use try-with-resources equivalent for InputStream
                inputStream.use { stream ->
                    Files.copy(stream, destinationPath, StandardCopyOption.REPLACE_EXISTING)
                }

                logger.info(
                    "Successfully downloaded '{}' from MinIO to '{}'.",
                    objectKey,
                    destinationPath,
                )
                true
            } catch (e: Exception) { // Catch more general exceptions for download
                logger.error(
                    "Error downloading object '{}' to '{}': {}",
                    objectKey,
                    destinationPath,
                    e.message,
                    e,
                )
                // Clean up potentially partially downloaded file
                try {
                    Files.deleteIfExists(destinationPath)
                } catch (_: Exception) {}
                false
            }
        }
    }

    /**
     * Streams an object from MinIO directly into a provided OutputStream. Caller owns the lifecycle
     * of the OutputStream; this method will not close it.
     */
    suspend fun copyObjectToOutput(objectKey: String, output: OutputStream): Boolean {
        return withContext(Dispatchers.IO) {
            var inputStream: InputStream? = null
            try {
                val args = GetObjectArgs.builder().bucket(bucketName).`object`(objectKey).build()
                inputStream = minioClient.getObject(args)
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var bytesRead: Int
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                }
                true
            } catch (e: CancellationException) {
                // Rethrow coroutine cancellations to avoid swallowing structured concurrency
                // signals
                throw e
            } catch (e: Exception) {
                logger.error("Error streaming object '{}' to output: {}", objectKey, e.message, e)
                false
            } finally {
                try {
                    inputStream?.close()
                } catch (_: Exception) {}
            }
        }
    }

    /**
     * Gets metadata (like ETag, Last-Modified, size) for an object without downloading it.
     *
     * @param objectKey The key of the object.
     * @return MinioObjectInfo containing metadata, or null if the object is not found or an error
     *   occurs.
     */
    suspend fun statObject(objectKey: String): MinioObjectInfo? {
        return withContext(Dispatchers.IO) {
            try {
                val args = StatObjectArgs.builder().bucket(bucketName).`object`(objectKey).build()
                val response: StatObjectResponse = minioClient.statObject(args)
                MinioObjectInfo(
                    objectKey = response.`object`(),
                    etag = response.etag(),
                    lastModified = response.lastModified(),
                    size = response.size(),
                )
            } catch (e: ErrorResponseException) {
                if (e.errorResponse().code() == "NoSuchKey") {
                    logger.warn("Object '{}' not found in bucket '{}'.", objectKey, bucketName)
                } else {
                    logger.error("Error getting stats for object '{}': {}", objectKey, e.message, e)
                }
                null
            } catch (e: Exception) {
                logger.error("Error getting stats for object '{}': {}", objectKey, e.message, e)
                null
            }
        }
    }

    /**
     * Deletes an object from MinIO.
     *
     * @param objectKey The key of the object to delete.
     * @return True if deletion was successful or object didn't exist, false on error.
     */
    suspend fun deleteObject(objectKey: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val args = RemoveObjectArgs.builder().bucket(bucketName).`object`(objectKey).build()
                minioClient.removeObject(args)
                logger.info(
                    "Successfully deleted object '{}' from MinIO (or it didn't exist).",
                    objectKey,
                )
                true
            } catch (e: Exception) {
                logger.error("Error deleting object '{}': {}", objectKey, e.message, e)
                false
            }
        }
    }

    /**
     * Uploads data from an InputStream to MinIO. Suitable for uploading dynamic content like source
     * code strings.
     *
     * @param objectKey The key (path) under which to store the object.
     * @param inputStream The stream containing the data to upload.
     * @param size The total size of the data in the stream (required by MinIO PutObjectArgs).
     * @param contentType Optional content type.
     * @return True if upload was successful, false otherwise.
     */
    suspend fun uploadStream(
        objectKey: String,
        inputStream: InputStream,
        size: Long,
        contentType: String = "application/octet-stream",
    ): Boolean {
        // Use try-with-resources for the input stream if passed from outside and needs closing,
        // but ByteArrayInputStream doesn't strictly need it.
        return withContext(Dispatchers.IO) {
            try {
                val args =
                    PutObjectArgs.builder()
                        .bucket(bucketName)
                        .`object`(objectKey)
                        .stream(
                            inputStream,
                            size,
                            -1,
                        ) // size is required, partSize -1 for unknown/single part
                        .contentType(contentType)
                        .build()
                minioClient.putObject(
                    args
                ) // putObject returns ObjectWriteResponse, we just check for exception
                logger.info("Successfully uploaded stream to MinIO as '{}'.", objectKey)
                true // Indicate success
            } catch (e: Exception) {
                logger.error("Error uploading stream to '{}': {}", objectKey, e.message, e)
                false // Indicate failure
            }
        }
    }
}
