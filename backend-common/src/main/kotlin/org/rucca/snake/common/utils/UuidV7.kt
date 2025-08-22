package org.rucca.snake.common.utils

import com.fasterxml.uuid.Generators
import java.util.UUID

/** UUIDv7 generator. */
object UuidV7 {
    fun generate(): UUID {
        return Generators.timeBasedEpochGenerator().generate()
    }
}
