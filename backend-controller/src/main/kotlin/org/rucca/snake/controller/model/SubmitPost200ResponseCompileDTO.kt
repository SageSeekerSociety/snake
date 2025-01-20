package org.rucca.snake.controller.model

import com.fasterxml.jackson.annotation.JsonProperty
import io.swagger.v3.oas.annotations.media.Schema

/**
 * @param success
 * @param diagnose
 */
data class SubmitPost200ResponseCompileDTO(
    @Schema(example = "null", description = "")
    @get:JsonProperty("success")
    val success: kotlin.Boolean? = null,
    @Schema(example = "null", description = "")
    @get:JsonProperty("diagnose")
    val diagnose: kotlin.String? = null,
) {}
