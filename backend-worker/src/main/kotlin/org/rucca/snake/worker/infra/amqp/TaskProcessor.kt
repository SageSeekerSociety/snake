package org.rucca.snake.worker.infra.amqp

import org.springframework.amqp.core.Message

/** Interface for processing tasks received from the message queue. */
interface TaskProcessor {

    /**
     * Processes a single message received from the queue. Implementations should parse the message,
     * delegate to the appropriate service, and handle exceptions to ensure proper ACK/NACK
     * signaling by the caller (e.g., TaskPoller).
     *
     * @param message The raw message received from RabbitMQ.
     * @throws Exception if processing fails critically and the message should be NACKed (or
     *   potentially retried/sent to DLQ).
     */
    suspend fun processMessage(message: Message)
}
