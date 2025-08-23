package org.rucca.snake.worker.config

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.amqp.AmqpRejectAndDontRequeueException // Import this
import org.springframework.amqp.core.*
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory
import org.springframework.amqp.rabbit.connection.ConnectionFactory
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.amqp.rabbit.listener.RabbitListenerContainerFactory
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer
import org.springframework.amqp.rabbit.support.DefaultMessagePropertiesConverter
import org.springframework.amqp.rabbit.support.MessagePropertiesConverter
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter
import org.springframework.amqp.support.converter.MessageConverter
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.retry.backoff.ExponentialBackOffPolicy
import org.springframework.retry.interceptor.MethodInvocationRecoverer // Import this
import org.springframework.retry.interceptor.RetryInterceptorBuilder
import org.springframework.retry.policy.SimpleRetryPolicy

@Configuration
class AmqpConfig {

    // --- 从配置注入名称 ---
    @Value("\${amqp.exchange.requests}") private lateinit var requestsExchangeName: String

    @Value("\${amqp.queue.compile}") private lateinit var compileQueueName: String

    @Value("\${amqp.queue.execute}") private lateinit var executeQueueName: String

    @Value("\${amqp.routingkey.compile}") private lateinit var compileRoutingKey: String

    @Value("\${amqp.routingkey.execute}") private lateinit var executeRoutingKey: String

    // --- (可选) 死信队列配置 ---
    @Value("\${amqp.exchange.requests-dlx:oj.requests.exchange.dlx}") // Default DLX name
    private lateinit var deadLetterExchangeName: String

    @Value("\${amqp.queue.compile-dlq:oj.compile.tasks.dlq}") // Default compile DLQ name
    private lateinit var compileDeadLetterQueueName: String

    @Value("\${amqp.queue.execute-dlq:oj.execute.tasks.dlq}") // Default execute DLQ name
    private lateinit var executeDeadLetterQueueName: String

    // --- (可选) 结果通知配置 ---
    @Value("\${amqp.exchange.results:oj.results.exchange}")
    private lateinit var resultsExchangeName: String
    @Value("\${amqp.routingkey.result:result.notify}") private lateinit var resultRoutingKey: String
    @Value("\${amqp.queue.results:oj.results.notify}") // 添加对结果队列名的注入
    private lateinit var resultsQueueName: String

    // Cache eviction fanout exchange
    @Value("\${amqp.exchange.cache:oj.cache.exchange}")
    private lateinit var cacheExchangeName: String

    // --- Listener Configuration ---
    @Value("\${amqp.listener.prefetch:8}") private val prefetchCount: Int = 8
    @Value("\${amqp.listener.concurrency:8}") private val concurrency: Int = 8
    @Value("\${amqp.listener.max-concurrency:8}") private val maxConcurrency: Int = 8
    @Value("\${amqp.listener.retry.initial-interval:1000}")
    private val retryInitialInterval: Long = 1000L
    @Value("\${amqp.listener.retry.max-interval:10000}") private val retryMaxInterval: Long = 10000L
    @Value("\${amqp.listener.retry.multiplier:2.0}") private val retryMultiplier: Double = 2.0
    @Value("\${amqp.listener.retry.max-attempts:3}") private val retryMaxAttempts: Int = 3

    // --- 定义 Beans ---

    // 请求 Exchange (例如，使用 Direct 类型)
    @Bean
    fun requestsExchange(): DirectExchange {
        return DirectExchange(requestsExchangeName, true, false) // durable=true, autoDelete=false
    }

    // 编译任务队列 (配置死信)
    @Bean
    fun compileQueue(): Queue {
        return QueueBuilder.durable(compileQueueName)
            .withArgument("x-dead-letter-exchange", deadLetterExchangeName) // 设置死信交换机
            // 死信的 routing key 可以不设置，默认使用原 routing key，或指定一个
            // .withArgument("x-dead-letter-routing-key", "dlq.compile")
            .build()
    }

    // 执行任务队列 (配置死信)
    @Bean
    fun executeQueue(): Queue {
        return QueueBuilder.durable(executeQueueName)
            .withArgument("x-dead-letter-exchange", deadLetterExchangeName)
            // .withArgument("x-dead-letter-routing-key", "dlq.execute")
            .build()
    }

    // 绑定编译队列到请求 Exchange
    @Bean
    fun compileBinding(
        @Qualifier("compileQueue") queue: Queue,
        @Qualifier("requestsExchange") exchange: DirectExchange,
    ): Binding {
        return BindingBuilder.bind(queue).to(exchange).with(compileRoutingKey)
    }

    // 绑定执行队列到请求 Exchange
    @Bean
    fun executeBinding(
        @Qualifier("executeQueue") queue: Queue,
        @Qualifier("requestsExchange") exchange: DirectExchange,
    ): Binding {
        return BindingBuilder.bind(queue).to(exchange).with(executeRoutingKey)
    }

    // --- (可选) 死信 Exchange 和 Queues ---
    @Bean
    fun deadLetterExchange(): DirectExchange {
        return DirectExchange(deadLetterExchangeName, true, false)
    }

    @Bean
    fun compileDeadLetterQueue(): Queue {
        return QueueBuilder.durable(compileDeadLetterQueueName).build()
    }

    @Bean
    fun executeDeadLetterQueue(): Queue {
        return QueueBuilder.durable(executeDeadLetterQueueName).build()
    }

    // 绑定死信队列 (使用原始路由键或特定DLQ路由键)
    @Bean
    fun compileDeadLetterBinding(
        @Qualifier("compileDeadLetterQueue") queue: Queue,
        @Qualifier("deadLetterExchange") exchange: DirectExchange,
    ): Binding {
        // Bind with the original routing key if no specific DLQ key is set on the main queue
        return BindingBuilder.bind(queue).to(exchange).with(compileRoutingKey)
        // Or bind with a specific DLQ routing key:
        // return BindingBuilder.bind(queue).to(exchange).with("dlq.compile")
    }

    @Bean
    fun executeDeadLetterBinding(
        @Qualifier("executeDeadLetterQueue") queue: Queue,
        @Qualifier("deadLetterExchange") exchange: DirectExchange,
    ): Binding {
        return BindingBuilder.bind(queue).to(exchange).with(executeRoutingKey)
        // Or bind with a specific DLQ routing key:
        // return BindingBuilder.bind(queue).to(exchange).with("dlq.execute")
    }

    // --- (可选) 结果通知 Exchange (如果 Worker 需要发送通知) ---
    @Bean
    fun resultsExchange(): DirectExchange {
        // Or FanoutExchange if multiple services need the notification
        return DirectExchange(resultsExchangeName, true, false)
    }

    // --- 定义结果队列和绑定 ---
    @Bean
    fun resultsQueue(): Queue {
        // 考虑是否需要死信队列等参数，根据实际需求配置
        return QueueBuilder.durable(resultsQueueName)
            // .withArgument("x-dead-letter-exchange", someOtherDeadLetterExchange) // 可选的死信配置
            .build()
    }

    @Bean
    fun resultBinding(
        @Qualifier("resultsQueue") queue: Queue,
        @Qualifier("resultsExchange") exchange: DirectExchange, // 确保类型与 resultsExchange Bean 匹配
    ): Binding {
        return BindingBuilder.bind(queue).to(exchange).with(resultRoutingKey)
    }

    // Fanout exchange for cache eviction (worker declares exchange; queue created by listener)
    @Bean
    fun cacheFanoutExchange(): FanoutExchange {
        return FanoutExchange(cacheExchangeName, true, false)
    }

    /**
     * Creates a Jackson2JsonMessageConverter Bean. Uses the primary ObjectMapper configured in the
     * application (e.g., from JacksonConfig).
     *
     * @param objectMapper Spring's configured ObjectMapper (with Kotlin & JavaTime modules).
     * @return The configured message converter.
     */
    @Bean
    fun jsonMessageConverter(objectMapper: ObjectMapper): MessageConverter {
        // Explicitly create the converter with the ObjectMapper that supports Kotlin data classes
        // and Java Time
        return Jackson2JsonMessageConverter(objectMapper)
    }

    /**
     * Configures the RabbitTemplate to use the JSON message converter. Spring Boot might
     * auto-configure this if a MessageConverter bean is present, but explicitly setting it ensures
     * correctness.
     *
     * @param connectionFactory The RabbitMQ connection factory.
     * @param messageConverter The configured Jackson2JsonMessageConverter bean.
     * @return The configured RabbitTemplate.
     */
    @Bean
    @Primary // Make this the default template if you have others
    fun rabbitTemplate(
        connectionFactory: ConnectionFactory,
        messageConverter: MessageConverter,
    ): RabbitTemplate {
        val template = RabbitTemplate(connectionFactory)
        template.messageConverter = messageConverter // Set the JSON converter
        // Add other template configurations if needed (e.g., reply timeout for RPC)
        return template
    }

    @Bean
    fun messagePropertiesConverter(): MessagePropertiesConverter {
        // Return the default implementation used by Spring AMQP
        return DefaultMessagePropertiesConverter()
        // If you need custom property conversion logic in the future,
        // you could create your own class implementing MessagePropertiesConverter here.
    }

    @Bean("rabbitListenerContainerFactory")
    fun rabbitListenerContainerFactory(
        connectionFactory: ConnectionFactory,
        jsonMessageConverter: MessageConverter, // Reuse the existing JSON converter
    ): RabbitListenerContainerFactory<SimpleMessageListenerContainer> {
        val factory = SimpleRabbitListenerContainerFactory()
        factory.setConnectionFactory(connectionFactory)
        factory.setMessageConverter(jsonMessageConverter)
        factory.setPrefetchCount(prefetchCount) // Set prefetch count
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL)

        factory.setConcurrentConsumers(concurrency)
        factory.setMaxConcurrentConsumers(maxConcurrency)

        // Define a MethodInvocationRecoverer that throws AmqpRejectAndDontRequeueException
        val recoverer =
            MethodInvocationRecoverer<Unit> { args, cause ->
                // args[0] is the Message for our listeners
                // Optional: Log here if specific logging for recovery is needed
                // val message = args.firstOrNull { it is Message } as? Message
                // logger.warn("Message recovery after retries for message:
                // ${message?.messageProperties?.correlationId}", cause)
                throw AmqpRejectAndDontRequeueException("Failed after max retries.", cause)
            }

        // Configure retry mechanism
        val retryInterceptor =
            RetryInterceptorBuilder.stateless()
                .retryPolicy(SimpleRetryPolicy(retryMaxAttempts))
                .backOffPolicy(
                    ExponentialBackOffPolicy().apply {
                        initialInterval = retryInitialInterval
                        maxInterval = retryMaxInterval
                        multiplier = retryMultiplier
                    }
                )
                .recoverer(recoverer) // Use the custom MethodInvocationRecoverer
                .build()

        factory.setAdviceChain(retryInterceptor)
        // Ensure messages are not requeued by default if an exception escapes the advice chain
        // or if no recoverer was configured/matched.
        // AmqpRejectAndDontRequeueException thrown by our recoverer achieves this specifically.
        factory.setDefaultRequeueRejected(false)

        return factory
    }
}
