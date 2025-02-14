package org.rucca.snake.worker.service

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import org.rucca.cheese.auth.config.ApplicationConfig
import org.rucca.cheese.auth.persistent.IdType
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile

@Service
class SubmitService(private val applicationConfig: ApplicationConfig) {
    fun submit(userId: IdType, src: MultipartFile): Pair<Boolean, String> {
        val userDirectory = File("${applicationConfig.dataDirectory}/$userId")
        if (!userDirectory.exists()) {
            userDirectory.mkdirs()
        }

        val currentTime =
            LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))
        val sourceFile = File(userDirectory, "$currentTime.cpp")
        src.transferTo(sourceFile)

        val processBuilder =
            ProcessBuilder(
                listOf(
                    applicationConfig.compilerPath,
                    *applicationConfig.compilerParameter.toTypedArray(),
                    sourceFile.absolutePath,
                    "-o",
                    File(userDirectory, "program").absolutePath,
                )
            )
        processBuilder.redirectErrorStream(true)
        val process = processBuilder.start()
        val exitCode = process.waitFor()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        return Pair(exitCode == 0, output)
    }
}
