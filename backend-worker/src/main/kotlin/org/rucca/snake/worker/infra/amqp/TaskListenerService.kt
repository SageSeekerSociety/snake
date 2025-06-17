package org.rucca.snake.worker.infra.amqp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.future.future
import org.slf4j.LoggerFactory
import org.springframework.amqp.core.Message
import org.springframework.amqp.rabbit.annotation.RabbitListener
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import java.util.concurrent.CompletableFuture

@Service
class TaskListenerService(
    private val taskProcessor: TaskProcessor,
    @Qualifier("applicationCoroutineScope") private val applicationScope: CoroutineScope // Injected CoroutineScope
) {

    private val logger = LoggerFactory.getLogger(TaskListenerService::class.java)

    @RabbitListener(
        queues = ["\${amqp.queue.compile}"], // Reference queue name from properties
        containerFactory = "rabbitListenerContainerFactory" // Specify the custom factory
    )
    fun receiveCompileTask(message: Message): CompletableFuture<Void?> { // 返回类型变更为 CompletableFuture<Void?>
        val jobId = message.messageProperties.correlationId ?: "[unknown_jobId-${'$'}{message.messageProperties.deliveryTag}]"
        logger.info(
            "Received compile task via listener for jobId: {} (deliveryTag: {}) from queue: {}",
            jobId,
            message.messageProperties.deliveryTag,
            message.messageProperties.consumerQueue
        )

        return applicationScope.future { // 使用 future 协程构建器
            try {
                taskProcessor.processMessage(message) // 调用挂起函数
                logger.info("Successfully processed compile task for jobId: {}", jobId)
            } catch (e: Exception) {
                // The retry interceptor in the container factory will handle retries based on the future failing.
                // If retries are exhausted, the message will be rejected (and sent to DLQ if configured).
                logger.error(
                    "Error processing compile task for jobId: {} from queue: {}. Error: {}",
                    jobId,
                    message.messageProperties.consumerQueue,
                    e.message,
                    e // Log the full exception
                )
                // IMPORTANT: Re-throw the exception to make the CompletableFuture complete exceptionally.
                // Spring AMQP will then handle it for retry/DLQ.
                throw e
            }
            null // CompletableFuture<Void?> 成功时返回 null
        }
    }

    @RabbitListener(
        queues = ["\${amqp.queue.execute}"], // Reference queue name from properties
        containerFactory = "rabbitListenerContainerFactory" // Specify the custom factory
    )
    fun receiveExecuteTask(message: Message): CompletableFuture<Void?> { // 返回类型变更为 CompletableFuture<Void?>
        val jobId = message.messageProperties.correlationId ?: "[unknown_jobId-${'$'}{message.messageProperties.deliveryTag}]"
        logger.info(
            "Received execute task via listener for jobId: {} (deliveryTag: {}) from queue: {}",
            jobId,
            message.messageProperties.deliveryTag,
            message.messageProperties.consumerQueue
        )

        return applicationScope.future { // 使用 future 协程构建器
            try {
                taskProcessor.processMessage(message) // 调用挂起函数
                logger.info("Successfully processed execute task for jobId: {}", jobId)
            } catch (e: Exception) {
                logger.error(
                    "Error processing execute task for jobId: {} from queue: {}. Error: {}",
                    jobId,
                    message.messageProperties.consumerQueue,
                    e.message,
                    e // Log the full exception
                )
                // Re-throw for DLQ processing by the container via CompletableFuture failure
                throw e
            }
            null // CompletableFuture<Void?> 成功时返回 null
        }
    }
}
