package org.rucca.snake.worker.utils

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class KeyedMutex<K> {
    private data class Ref(val mutex: Mutex = Mutex(), val cnt: AtomicInteger = AtomicInteger(0))

    private val map = ConcurrentHashMap<K, Ref>()

    suspend fun <T> withLock(key: K, block: suspend () -> T): T {
        val ref = map.compute(key) { _, cur -> (cur ?: Ref()).also { it.cnt.incrementAndGet() } }!!
        try {
            return ref.mutex.withLock { block() }
        } finally {
            if (ref.cnt.decrementAndGet() == 0) {
                // remove only if same instance (避免误删新创建的锁)
                map.remove(key, ref)
            }
        }
    }
}
