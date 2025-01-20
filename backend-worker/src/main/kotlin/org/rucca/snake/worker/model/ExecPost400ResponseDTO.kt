package org.rucca.snake.worker.model

import com.fasterxml.jackson.annotation.JsonProperty
import io.swagger.v3.oas.annotations.media.Schema

/**
 * @param success
 * @param error
 */
data class ExecPost400ResponseDTO(
    @Schema(example = "null", description = "")
    @get:JsonProperty("success")
    val success: kotlin.Boolean? = null,
    @Schema(example = "null", description = "")
    @get:JsonProperty("error")
    val error: kotlin.String? = null
) {}
