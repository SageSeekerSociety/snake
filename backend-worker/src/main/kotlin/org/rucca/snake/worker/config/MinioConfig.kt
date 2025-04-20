package org.rucca.snake.worker.config

import io.minio.MinioClient
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class MinioConfig {
    private val logger = LoggerFactory.getLogger(MinioConfig::class.java)

    @Value("\${minio.endpoint}") private lateinit var endpoint: String

    @Value("\${minio.accessKey}") private lateinit var accessKey: String

    @Value("\${minio.secretKey}") private lateinit var secretKey: String

    @Bean
    fun minioClient(): MinioClient {
        logger.info("Initializing Minio client with endpoint: {}", endpoint)
        try {
            return MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build()
        } catch (e: Exception) {
            logger.error("Error initializing Minio client", e)
            throw IllegalStateException("Failed to initialize Minio client", e)
        }
    }
}
