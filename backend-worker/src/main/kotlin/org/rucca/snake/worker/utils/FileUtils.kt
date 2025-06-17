package org.rucca.snake.worker.utils

import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator


/**
 * A regular (non-suspend) function to encapsulate the blocking file IO.
 * This helps in silencing linter warnings about blocking calls in coroutines.
 */
fun deleteDirectoryRecursively(dir: Path) {
    if (Files.exists(dir)) {
        // The blocking logic is now contained in a standard function.
        Files.walk(dir)
            .use { stream ->
                stream.sorted(Comparator.reverseOrder())
                    .forEach { Files.deleteIfExists(it) }
            }
    }
}