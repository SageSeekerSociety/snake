package org.rucca.snake.controller.infra.amqp

import java.time.Instant
import org.rucca.snake.common.domain.model.CacheEvictMessage
import org.slf4j.LoggerFactory
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class CacheEvictionPublisher(
    private val rabbitTemplate: RabbitTemplate,
    @Value("\${amqp.exchange.cache:oj.cache.exchange}") private val cacheExchangeName: String,
) {
    private val logger = LoggerFactory.getLogger(CacheEvictionPublisher::class.java)

    fun publishEvict(objectKey: String) {
        val message = CacheEvictMessage(objectKey = objectKey, timestamp = Instant.now())
        try {
            // Fanout exchange ignores routing key
            rabbitTemplate.convertAndSend(cacheExchangeName, "", message)
            logger.info(
                "Published cache evict for objectKey={} to exchange={}",
                objectKey,
                cacheExchangeName,
            )
        } catch (e: Exception) {
            logger.error(
                "Failed to publish cache evict for objectKey={}: {}",
                objectKey,
                e.message,
                e,
            )
        }
    }
}
