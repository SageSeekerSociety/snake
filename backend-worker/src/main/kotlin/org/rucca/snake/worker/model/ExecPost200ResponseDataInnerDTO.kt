package org.rucca.snake.worker.model

import com.fasterxml.jackson.annotation.JsonProperty
import io.swagger.v3.oas.annotations.media.Schema
import javax.validation.Valid

/**
 * @param userId
 * @param output
 * @param error
 */
data class ExecPost200ResponseDataInnerDTO(
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("userId", required = true)
    val userId: kotlin.Long,
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("output", required = true)
    val output: kotlin.String,
    @field:Valid
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("error", required = true)
    val error: kotlin.Any,
) {}
