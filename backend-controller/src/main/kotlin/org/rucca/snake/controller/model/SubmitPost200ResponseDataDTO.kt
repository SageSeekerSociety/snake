package org.rucca.snake.controller.model

import com.fasterxml.jackson.annotation.JsonProperty
import io.swagger.v3.oas.annotations.media.Schema

/**
 * @param success
 * @param diagnose
 */
data class SubmitPost200ResponseDataDTO(
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("success", required = true)
    val success: kotlin.Boolean,
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("diagnose", required = true)
    val diagnose: kotlin.String,
) {}
