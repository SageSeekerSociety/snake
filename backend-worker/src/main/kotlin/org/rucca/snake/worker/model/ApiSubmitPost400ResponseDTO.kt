package org.rucca.snake.worker.model

import com.fasterxml.jackson.annotation.JsonProperty
import io.swagger.v3.oas.annotations.media.Schema
import javax.validation.Valid

/**
 * @param error
 * @param compile
 */
data class ApiSubmitPost400ResponseDTO(
    @Schema(example = "null", description = "")
    @get:JsonProperty("error")
    val error: kotlin.String? = null,
    @field:Valid
    @Schema(example = "null", description = "")
    @get:JsonProperty("compile")
    val compile: kotlin.Any? = null
) {}
