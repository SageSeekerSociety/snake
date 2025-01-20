package org.rucca.snake.worker.model

import com.fasterxml.jackson.annotation.JsonProperty
import io.swagger.v3.oas.annotations.media.Schema
import javax.validation.Valid

/**
 * @param sid
 * @param output
 * @param result
 * @param error
 */
data class ExecPost200ResponseDataInnerDTO(
    @Schema(example = "null", description = "")
    @get:JsonProperty("sid")
    val sid: kotlin.String? = null,
    @Schema(example = "null", description = "")
    @get:JsonProperty("output")
    val output: kotlin.String? = null,
    @field:Valid
    @Schema(example = "null", description = "")
    @get:JsonProperty("result")
    val result: kotlin.Any? = null,
    @Schema(example = "null", description = "")
    @get:JsonProperty("error")
    val error: kotlin.String? = null
) {}
