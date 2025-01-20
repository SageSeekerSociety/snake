package org.rucca.snake.controller.model

import com.fasterxml.jackson.annotation.JsonProperty
import io.swagger.v3.oas.annotations.media.Schema

/** @param error */
data class SubmitPost403ResponseDTO(
    @Schema(example = "Submission Rejected", description = "")
    @get:JsonProperty("error")
    val error: kotlin.String? = null
) {}
