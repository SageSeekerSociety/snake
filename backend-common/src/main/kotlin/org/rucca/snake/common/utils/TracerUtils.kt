package org.rucca.snake.common.utils

import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.context.Context
import io.opentelemetry.extension.kotlin.asContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.use
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

/**
 * Executes a suspending block of code within a new OpenTelemetry Span. This function handles the
 * creation, activation, error recording, and closing of the span.
 *
 * @param T The return type of the code block.
 * @param spanName The name for the new span.
 * @param block The suspending lambda to be executed within the span.
 * @return The result of the executed block.
 */
suspend fun <T> Tracer.withSuspendingSpan(
    spanName: String,
    kind: SpanKind = SpanKind.INTERNAL,
    ctx: CoroutineContext = EmptyCoroutineContext,
    block: suspend () -> T,
): T {
    val parent: Context = Context.current()
    val span = this.spanBuilder(spanName).setSpanKind(kind).setParent(parent).startSpan()

    return try {
        withContext(ctx + parent.with(span).asContextElement()) { block() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        span.recordException(e)
        span.setStatus(StatusCode.ERROR, e.message ?: "error")
        throw e
    } finally {
        span.end()
    }
}

fun <T> Tracer.withSpan(spanName: String, kind: SpanKind = SpanKind.INTERNAL, block: () -> T): T {
    val parent = Context.current()
    val span = this.spanBuilder(spanName).setSpanKind(kind).setParent(parent).startSpan()

    try {
        span.makeCurrent().use {
            return block()
        }
    } catch (e: CancellationException) {
        // Cancellation is expected; don't record as an error
        span.setStatus(StatusCode.UNSET)
        throw e
    } catch (e: Exception) {
        span.recordException(e)
        span.setStatus(StatusCode.ERROR, e.message ?: "error")
        throw e
    } finally {
        span.end()
    }
}
