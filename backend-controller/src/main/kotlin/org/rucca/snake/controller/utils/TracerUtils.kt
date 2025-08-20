package org.rucca.snake.controller.utils

import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.context.Context
import io.opentelemetry.extension.kotlin.asContextElement
import kotlinx.coroutines.withContext

/**
 * Executes a suspending block of code within a new OpenTelemetry Span.
 * This function handles the creation, activation, error recording, and closing of the span.
 *
 * @param T The return type of the code block.
 * @param spanName The name for the new span.
 * @param block The suspending lambda to be executed within the span.
 * @return The result of the executed block.
 */
suspend fun <T> Tracer.withSuspendingSpan(spanName: String, block: suspend () -> T): T {
    val span = this.spanBuilder(spanName).startSpan()

    return try {
        withContext(Context.current().with(span).asContextElement()) {
            block()
        }
    } catch (e: Exception) {
        span.recordException(e)
        throw e
    } finally {
        span.end()
    }
}

fun <T> Tracer.withSpan(spanName: String, block: () -> T): T {
    val span = this.spanBuilder(spanName).startSpan()
    try {
        span.makeCurrent().use {
            return block()
        }
    } catch (e: Exception) {
        span.recordException(e)
        throw e
    } finally {
        span.end()
    }
}