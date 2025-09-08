package org.rucca.snake.controller.domain.controller

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.runBlocking
import org.rucca.cheese.auth.annotation.Guard
import org.rucca.snake.controller.domain.service.ExportService
import org.rucca.snake.controller.infra.throttle.RateLimited
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody

@RestController
@RequestMapping("/compile/export")
class ExportController(private val exportService: ExportService) {
    private val logger = LoggerFactory.getLogger(ExportController::class.java)

    @Guard("export", "program")
    @RateLimited("export")
    @GetMapping("/latest-sources.zip", produces = ["application/zip"])
    fun exportLatestSourcesZip(): ResponseEntity<StreamingResponseBody> {
        val ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val filename = "latest-sources-$ts.zip"

        val body = StreamingResponseBody { outputStream ->
            runBlocking { exportService.writeLatestSourcesZip(outputStream) }
        }

        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("application/zip"))
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"$filename\"")
            .body(body)
    }
}
