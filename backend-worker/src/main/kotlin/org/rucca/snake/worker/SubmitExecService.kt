package org.rucca.snake.worker

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.*
import org.rucca.cheese.auth.persistent.IdType
import org.rucca.snake.worker.error.ExecutionError
import org.rucca.snake.worker.error.ProgramNotFoundError
import org.rucca.snake.worker.model.ExecPost200ResponseDataInnerDTO
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile

@Service
class SubmitExecService(private val applicationConfig: ApplicationConfig) {
    fun submit(userId: IdType, src: MultipartFile): Pair<Boolean, String> {
        val userDirectory = File("${applicationConfig.dataDirectory}/$userId")
        if (!userDirectory.exists()) userDirectory.mkdirs()
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

    suspend fun exec(userId: IdType, input: String): ExecPost200ResponseDataInnerDTO {
        val userDirectory = File("${applicationConfig.dataDirectory}/$userId")
        val programFile = File(userDirectory, "program")
        if (!programFile.exists())
            return ExecPost200ResponseDataInnerDTO(
                userId = userId,
                output = "",
                sandbox = "",
                error = ProgramNotFoundError(userId = userId),
            )

        return withContext(Dispatchers.IO) {
            val logFile = File(userDirectory, "nsjail.log")
            logFile.deleteOnExit()
            logFile.createNewFile()
            val processBuilder =
                ProcessBuilder(
                    listOf(
                        applicationConfig.nsjailPath,
                        *applicationConfig.nsjailParameter.toTypedArray(),
                        "--chroot",
                        userDirectory.absolutePath,
                        "--log",
                        logFile.absolutePath,
                        "--",
                        "/program",
                    )
                )
            processBuilder.redirectErrorStream(true)
            val process = processBuilder.start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val sandbox = logFile.readLines().joinToString("\n")
            val exitCode = process.waitFor()
            ExecPost200ResponseDataInnerDTO(
                userId = userId,
                output = output,
                sandbox = sandbox,
                if (exitCode == 0) null else ExecutionError(exitCode),
            )
        }
    }

    fun exec(userIds: List<IdType>, input: String): List<ExecPost200ResponseDataInnerDTO> {
        return runBlocking { userIds.map { async { exec(it, input) } }.awaitAll() }
    }
}
