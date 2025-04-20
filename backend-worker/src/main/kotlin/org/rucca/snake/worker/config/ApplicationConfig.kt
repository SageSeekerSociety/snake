/*
 *  Description: This file defines the application configuration properties.
 *               It is used to read the properties from src/main/resources/application.properties
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package org.rucca.snake.worker.config

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

data class NsjailProperties(
    var path: String = "/usr/local/src/nsjail/nsjail",
    var baseParameters: List<String> = listOf("-M", "o", "-R", "/lib"),
)

data class CacheProperties(
    var basePath: String = "/tmp/snake/cache",
    var ttl: Duration = Duration.ofDays(1),
)

data class ConcurrencyProperties(var nsjailPermits: Int = 1, var maxWorkerJobs: Int = 2)

data class CorsProperties(var allowedOrigins: List<String> = listOf("*"))

data class PollingProperties(
    var idleDelayMs: Long = 50,
    var busyDelayMs: Long = 100,
    var receiveTimeoutMs: Long = 100,
)

@Component
@ConfigurationProperties(prefix = "application")
data class ApplicationConfig(
    var compilerPath: String = "/usr/bin/clang++",
    var compilerParameter: List<String> =
        listOf(
            "-std=c++17",
            "-O2",
            "-lm",
            "-pthread",
            "-fno-omit-frame-pointer",
            "-fstack-protector-strong",
            "-fno-strict-aliasing",
            "-fno-unwind-tables",
            "-fno-asynchronous-unwind-tables",
            "-fno-stack-protector",
        ),
    var dataDirectory: String = "/tmp/snake",
    var cache: CacheProperties = CacheProperties(),
    var concurrency: ConcurrencyProperties = ConcurrencyProperties(),
    var cors: CorsProperties = CorsProperties(),
    var nsjail: NsjailProperties = NsjailProperties(),
    var polling: PollingProperties = PollingProperties(),
)
