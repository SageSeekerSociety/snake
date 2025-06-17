package org.rucca.snake.worker.infra.amqp

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.any
import org.springframework.amqp.core.Message
import org.springframework.amqp.core.MessageProperties
import org.rucca.snake.worker.config.ApplicationConfig // Mock this as it's a constructor dependency

class TaskListenerServiceTest {

    // Mocks for constructor dependencies
    private val mockTaskProcessor: TaskProcessor = mock()
    private val mockAppConfig: ApplicationConfig = mock() // TaskListenerService requires ApplicationConfig

    // Instance of the service under test, with mocked dependencies
    private val listenerService = TaskListenerService(mockTaskProcessor, mockAppConfig)

    @Test
    fun `receiveCompileTask should call taskProcessor processMessage`() {
        // Arrange: Create a sample message
        val messageProperties = MessageProperties()
        messageProperties.correlationId = "test-compile-job-001"
        messageProperties.consumerQueue = "oj.compile.tasks" // Example queue name
        messageProperties.deliveryTag = 123L // Example delivery tag
        val testPayload = "{\"sourceCode\": \"print('hello')\"}"
        val message = Message(testPayload.toByteArray(), messageProperties)

        // Act: Call the method under test
        listenerService.receiveCompileTask(message)

        // Assert: Verify that taskProcessor.processMessage was called with the exact message
        verify(mockTaskProcessor).processMessage(message)
    }

    @Test
    fun `receiveExecuteTask should call taskProcessor processMessage`() {
        // Arrange: Create a sample message
        val messageProperties = MessageProperties()
        messageProperties.correlationId = "test-execute-job-002"
        messageProperties.consumerQueue = "oj.execute.tasks" // Example queue name
        messageProperties.deliveryTag = 456L // Example delivery tag
        val testPayload = "{\"compiledArtifactPath\": \"/path/to/output\"}"
        val message = Message(testPayload.toByteArray(), messageProperties)

        // Act: Call the method under test
        listenerService.receiveExecuteTask(message)

        // Assert: Verify that taskProcessor.processMessage was called with the exact message
        verify(mockTaskProcessor).processMessage(message)
    }

    @Test
    fun `receiveCompileTask should re-throw exception from taskProcessor`() {
        // Arrange: Create a sample message
        val messageProperties = MessageProperties()
        messageProperties.correlationId = "test-compile-error-job-003"
        messageProperties.consumerQueue = "oj.compile.tasks"
        messageProperties.deliveryTag = 789L
        val testPayload = "{\"badData\": \"trigger error\"}"
        val message = Message(testPayload.toByteArray(), messageProperties)

        // Configure mockTaskProcessor to throw an exception when processMessage is called
        val expectedException = RuntimeException("Processing failed!")
        doThrow(expectedException).`when`(mockTaskProcessor).processMessage(any()) // Use any() or be specific with message

        // Act & Assert: Call the method and verify that the same exception is re-thrown
        val thrownException = assertThrows<RuntimeException> {
            listenerService.receiveCompileTask(message)
        }
        assert(thrownException == expectedException) { "The thrown exception was not the expected one." }

        // Verify that taskProcessor.processMessage was indeed called
        verify(mockTaskProcessor).processMessage(message)
    }

    @Test
    fun `receiveExecuteTask should re-throw exception from taskProcessor`() {
        // Arrange: Create a sample message
        val messageProperties = MessageProperties()
        messageProperties.correlationId = "test-execute-error-job-004"
        messageProperties.consumerQueue = "oj.execute.tasks"
        messageProperties.deliveryTag = 101L
        val testPayload = "{\"badData\": \"trigger error execute\"}"
        val message = Message(testPayload.toByteArray(), messageProperties)

        // Configure mockTaskProcessor to throw an exception
        val expectedException = RuntimeException("Execution processing failed!")
        doThrow(expectedException).`when`(mockTaskProcessor).processMessage(message) // Can be specific with message

        // Act & Assert: Call the method and verify that the same exception is re-thrown
        val thrownException = assertThrows<RuntimeException> {
            listenerService.receiveExecuteTask(message)
        }
        assert(thrownException == expectedException) { "The thrown exception was not the expected one for execute task." }

        // Verify that taskProcessor.processMessage was called
        verify(mockTaskProcessor).processMessage(message)
    }
}
