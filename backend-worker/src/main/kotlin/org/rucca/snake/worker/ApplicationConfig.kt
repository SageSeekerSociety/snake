/*
 *  Description: This file defines the application configuration properties.
 *               It is used to read the properties from src/main/resources/application.properties
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package org.rucca.snake.worker

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@Component
@ConfigurationProperties(prefix = "application")
class ApplicationConfig {
    lateinit var compilerPath: String
    lateinit var compilerParameter: List<String>
    lateinit var dataDirectory: String

    lateinit var nsjailPath: String
    lateinit var nsjailParameter: List<String>

    var cors: CorsConfig = CorsConfig()

    class CorsConfig {
        lateinit var allowedOrigins: List<String>
    }
}
