package org.rucca.snake.worker.service

import org.rucca.cheese.auth.config.ApplicationConfig
import org.springframework.stereotype.Service

@Service
class SubmitService(private val applicationConfig: ApplicationConfig) {
    fun submit() {}
}
