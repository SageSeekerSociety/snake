package org.rucca.snake.worker.infra.amqp

import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.context.Context
import io.opentelemetry.context.Scope
import io.opentelemetry.context.propagation.TextMapGetter
import io.opentelemetry.extension.kotlin.asContextElement
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.future.future
import org.slf4j.LoggerFactory
import org.springframework.amqp.core.Message
import org.springframework.amqp.rabbit.annotation.RabbitListener
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service

@Service
class TaskListenerService(
    private val taskProcessor: TaskProcessor,
    @Qualifier("applicationCoroutineScope") private val applicationScope: CoroutineScope,
    openTelemetry: OpenTelemetry,
) {
    private val logger = LoggerFactory.getLogger(TaskListenerService::class.java)

    private val tracer = openTelemetry.getTracer(TaskListenerService::class.java.name)
    private val propagator = openTelemetry.propagators.textMapPropagator

    private object AmqpGetter : TextMapGetter<Message> {
        override fun keys(carrier: Message): Iterable<String> =
            carrier.messageProperties.headers.keys

        override fun get(carrier: Message?, key: String): String? {
            val v = carrier?.messageProperties?.headers?.get(key) ?: return null
            return when (v) {
                is ByteArray -> String(v)
                else -> v.toString()
            }
        }
    }

    private fun handleMessage(message: Message, operation: String): CompletableFuture<Void?> {
        val props = message.messageProperties
        val jobId = props.correlationId ?: "[unknown_jobId-${props.deliveryTag}]"

        val extracted: Context = propagator.extract(Context.current(), message, AmqpGetter)

        val extractedSpanContext = Span.fromContext(extracted).spanContext
        logger.info(
            "Worker extracted trace: traceId={}, spanId={}, isRemote={}, queue={}",
            extractedSpanContext.traceId,
            extractedSpanContext.spanId,
            extractedSpanContext.isRemote,
            props.consumerQueue,
        )

        val consumerSpan =
            tracer
                .spanBuilder("$operation.receive")
                .setSpanKind(SpanKind.CONSUMER)
                .setParent(extracted)
                .setAttribute(AttributeKey.stringKey("messaging.system"), "rabbitmq")
                .setAttribute(
                    AttributeKey.stringKey("messaging.destination.name"),
                    props.consumerQueue ?: "",
                )
                .setAttribute(AttributeKey.stringKey("messaging.operation"), "process")
                .setAttribute(
                    AttributeKey.stringKey("messaging.rabbitmq.delivery_tag"),
                    props.deliveryTag.toString(),
                )
                .setAttribute(AttributeKey.stringKey("app.job_id"), jobId)
                .startSpan()

        val ctxWithConsumer = extracted.with(consumerSpan)

        return applicationScope.future(ctxWithConsumer.asContextElement()) {
            ctxWithConsumer.makeCurrent().use { _: Scope ->
                try {
                    taskProcessor.processMessage(message)

                    consumerSpan.setStatus(StatusCode.OK)
                    logger.info("Successfully processed {} task for jobId={}", operation, jobId)
                } catch (e: Exception) {
                    consumerSpan.recordException(e)
                    consumerSpan.setStatus(StatusCode.ERROR, e.message ?: "error")
                    logger.error(
                        "Error processing {} task for jobId={} from queue={}. Error={}",
                        operation,
                        jobId,
                        props.consumerQueue,
                        e.message,
                        e,
                    )
                    throw e
                } finally {
                    consumerSpan.end()
                }
            }
            null
        }
    }

    @RabbitListener(
        queues = ["\${amqp.queue.compile}"],
        containerFactory = "rabbitListenerContainerFactory",
    )
    fun receiveCompileTask(message: Message): CompletableFuture<Void?> =
        handleMessage(message, "compile")

    @RabbitListener(
        queues = ["\${amqp.queue.execute}"],
        containerFactory = "rabbitListenerContainerFactory",
    )
    fun receiveExecuteTask(message: Message): CompletableFuture<Void?> =
        handleMessage(message, "execute")
}
