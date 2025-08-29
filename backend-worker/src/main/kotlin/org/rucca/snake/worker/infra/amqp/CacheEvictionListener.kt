package org.rucca.snake.worker.infra.amqp

import org.rucca.snake.common.domain.model.CacheEvictMessage
import org.rucca.snake.worker.domain.service.CacheManager
import org.slf4j.LoggerFactory
import org.springframework.amqp.rabbit.annotation.Exchange
import org.springframework.amqp.rabbit.annotation.Queue
import org.springframework.amqp.rabbit.annotation.QueueBinding
import org.springframework.amqp.rabbit.annotation.RabbitListener
import org.springframework.stereotype.Component

@Component
class CacheEvictionListener(private val cacheManager: CacheManager) {
    private val logger = LoggerFactory.getLogger(CacheEvictionListener::class.java)

    @RabbitListener(
        containerFactory = "autoAckRabbitListenerContainerFactory",
        bindings =
            [
                QueueBinding(
                    value =
                        Queue(
                            value = "",
                            durable = "false",
                            exclusive = "true",
                            autoDelete = "true",
                        ),
                    exchange =
                        Exchange(
                            name = "\${amqp.exchange.cache:oj.cache.exchange}",
                            type = "fanout",
                        ),
                    key = [""],
                )
            ],
    )
    fun onCacheEvict(message: CacheEvictMessage) {
        try {
            cacheManager.invalidateMetadata(message.objectKey)
            logger.info("Cache eviction applied for objectKey={}", message.objectKey)
        } catch (e: Exception) {
            logger.error(
                "Failed to evict cache for objectKey=${message.objectKey}: ${e.message}",
                e,
            )
        }
    }
}
