package org.rucca.snake.worker.infra.amqp

import org.rucca.snake.worker.config.ApplicationConfig // Assuming this is needed for TaskProcessor or logging
import org.slf4j.LoggerFactory
import org.springframework.amqp.core.Message
import org.springframework.amqp.rabbit.annotation.RabbitListener
import org.springframework.stereotype.Service

@Service
class TaskListenerService(
    private val taskProcessor: TaskProcessor,
    private val applicationConfig: ApplicationConfig // If TaskProcessor or other logic needs it
) {

    private val logger = LoggerFactory.getLogger(TaskListenerService::class.java)

    @RabbitListener(
        queues = ["\${amqp.queue.compile}"], // Reference queue name from properties
        containerFactory = "rabbitListenerContainerFactory" // Specify the custom factory
    )
    fun receiveCompileTask(message: Message) {
        val jobId = message.messageProperties.correlationId ?: "[unknown_jobId-${message.messageProperties.deliveryTag}]"
        logger.info(
            "Received compile task via listener for jobId: {} (deliveryTag: {}) from queue: {}",
            jobId,
            message.messageProperties.deliveryTag,
            message.messageProperties.consumerQueue
        )
        try {
            taskProcessor.processMessage(message) // Delegate to existing processor
            logger.info("Successfully processed compile task for jobId: {}", jobId)
        } catch (e: Exception) {
            // The retry interceptor in the container factory will handle retries.
            // If retries are exhausted, the message will be rejected (and sent to DLQ if configured).
            // So, we just log the error here after all retries have failed.
            logger.error(
                "Error processing compile task for jobId: {} after retries from queue: {}. Error: {}",
                jobId,
                message.messageProperties.consumerQueue,
                e.message,
                e // Log the full exception
            )
            // IMPORTANT: Re-throw the exception if you want the message to be rejected by the listener container
            // and potentially sent to a DLQ after retries are exhausted by the RetryInterceptor.
            // If you catch it and don't re-throw, Spring AMQP will consider it successfully processed.
            throw e
        }
    }

    @RabbitListener(
        queues = ["\${amqp.queue.execute}"], // Reference queue name from properties
        containerFactory = "rabbitListenerContainerFactory" // Specify the custom factory
    )
    fun receiveExecuteTask(message: Message) {
        val jobId = message.messageProperties.correlationId ?: "[unknown_jobId-${message.messageProperties.deliveryTag}]"
        logger.info(
            "Received execute task via listener for jobId: {} (deliveryTag: {}) from queue: {}",
            jobId,
            message.messageProperties.deliveryTag,
            message.messageProperties.consumerQueue
        )
        try {
            taskProcessor.processMessage(message) // Delegate to existing processor
            logger.info("Successfully processed execute task for jobId: {}", jobId)
        } catch (e: Exception) {
            logger.error(
                "Error processing execute task for jobId: {} after retries from queue: {}. Error: {}",
                jobId,
                message.messageProperties.consumerQueue,
                e.message,
                e // Log the full exception
            )
            // Re-throw for DLQ processing by the container
            throw e
        }
    }
}
