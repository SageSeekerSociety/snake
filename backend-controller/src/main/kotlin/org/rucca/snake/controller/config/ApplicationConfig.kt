/*
 *  Description: This file defines the application configuration properties.
 *               It is used to read the properties from src/main/resources/application.properties
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package org.rucca.snake.controller.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

data class CorsProperties(var allowedOrigins: List<String> = listOf("*"))

@Component
@ConfigurationProperties(prefix = "application")
data class ApplicationConfig(
    var workerUrls: List<String> = listOf("http://localhost:8080"),
    var cors: CorsProperties = CorsProperties(),
)
