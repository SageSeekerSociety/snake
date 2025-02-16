package org.rucca.snake.controller.model

import com.fasterxml.jackson.annotation.JsonProperty
import io.swagger.v3.oas.annotations.media.Schema
import javax.validation.Valid

/**
 * @param userId
 * @param output
 * @param sandbox
 * @param error
 */
data class ExecPost200ResponseDataInnerDTO(
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("userId", required = true)
    val userId: kotlin.Long,
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("output", required = true)
    val output: kotlin.String,
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("sandbox", required = true)
    val sandbox: kotlin.String,
    @field:Valid
    @Schema(example = "null", description = "")
    @get:JsonProperty("error")
    val error: kotlin.Any? = null,
) {}
