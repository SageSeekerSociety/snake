package org.rucca.snake.controller.domain.service

import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.rucca.snake.common.infra.persistence.repository.CompilationJobRepository
import org.rucca.snake.common.infra.persistence.repository.UserRepository
import org.rucca.snake.controller.infra.storage.MinioService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class ExportService(
    private val compilationJobRepository: CompilationJobRepository,
    private val userRepository: UserRepository,
    private val minioService: MinioService,
) {
    private val logger = LoggerFactory.getLogger(ExportService::class.java)

    suspend fun writeLatestSourcesZip(output: java.io.OutputStream) {
        withContext(Dispatchers.IO) {
            ZipOutputStream(output).use { zos ->
                // 1) Query latest successful source per user
                val rows = compilationJobRepository.findLatestSuccessSourcePerUser()
                if (rows.isEmpty()) return@use

                // 2) Build user name map
                val userIds: List<Int> = rows.map { it.getUserId().toInt() }
                val users = userRepository.findAllById(userIds)
                val usernameMap: Map<Long, String> =
                    users.associate { u -> (u.id!!.toLong()) to (u.username ?: "user-${u.id}") }

                // 3) Track used names to avoid collision
                val usedNames = mutableSetOf<String>()

                rows.forEach { row ->
                    val userId = row.getUserId()
                    val objectKey = row.getSourceCodeRef()
                    val username = sanitizeUsername(usernameMap[userId] ?: "user-$userId")
                    val ext = guessExtension(objectKey)
                    var entryName = "$username.$ext"
                    if (!usedNames.add(entryName)) {
                        entryName = "$username-$userId.$ext"
                        usedNames.add(entryName)
                    }

                    try {
                        val entry = ZipEntry(entryName)
                        zos.putNextEntry(entry)
                        val ok = minioService.copyObjectToOutput(objectKey, zos)
                        zos.closeEntry()
                        if (!ok)
                            logger.warn("Failed to stream object {} for user {}", objectKey, userId)
                    } catch (e: Exception) {
                        logger.error(
                            "Error adding entry for user {} key {}: {}",
                            userId,
                            objectKey,
                            e.message,
                            e,
                        )
                        try {
                            zos.closeEntry()
                        } catch (_: Exception) {}
                    }
                }
                zos.flush()
            }
        }
    }

    private fun sanitizeUsername(input: String): String {
        return input
            .map { ch -> if (ch.isLetterOrDigit() || ch == '_') ch else '_' }
            .joinToString("")
    }

    private fun guessExtension(objectKey: String): String {
        val name = objectKey.substringAfterLast('/')
        val ext = name.substringAfterLast('.', missingDelimiterValue = "")
        return if (ext.isNotBlank() && ext.length <= 10) ext else "cpp"
    }
}
