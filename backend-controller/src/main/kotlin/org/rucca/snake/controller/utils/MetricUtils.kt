package org.rucca.snake.controller.utils

import io.micrometer.core.instrument.Clock
import io.micrometer.core.instrument.Timer
import java.util.concurrent.TimeUnit

suspend fun <T> Timer.recordSuspendable(block: suspend () -> T): T {
    val start = Clock.SYSTEM.monotonicTime()
    try {
        return block()
    } finally {
        val durationNanos = Clock.SYSTEM.monotonicTime() - start
        this.record(durationNanos, TimeUnit.NANOSECONDS)
    }
}
