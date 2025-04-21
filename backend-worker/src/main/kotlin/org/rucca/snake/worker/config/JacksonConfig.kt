package org.rucca.snake.worker.config // Ensure package is correct for Worker module

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule // Import KotlinModule
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary

@Configuration
class JacksonConfig {
    @Bean
    @Primary // Make this the primary ObjectMapper for injection
    fun objectMapper(): ObjectMapper {
        return ObjectMapper().apply {
            // Register Kotlin module for data classes, non-null assertions etc.
            // Use KotlinModule.Builder() for more configuration options if needed
            registerModule(KotlinModule.Builder().build()) // Ensure Kotlin module is registered

            // Register JavaTimeModule for Instant, LocalDate etc. serialization/deserialization
            registerModule(JavaTimeModule())

            // Optional: Configure date/time format (Spring Boot default is usually ISO-8601)
            // disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

            // Optional: Disable failing on unknown properties during deserialization
            // configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

            // Other customizations...
        }
    }
}