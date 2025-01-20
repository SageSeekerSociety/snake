package org.rucca.snake.controller.model

import com.fasterxml.jackson.annotation.JsonProperty
import io.swagger.v3.oas.annotations.media.Schema

/**
 * @param uid Array of user IDs
 * @param input Input for the execution
 */
data class ExecPostRequestDTO(
    @Schema(example = "null", description = "Array of user IDs")
    @get:JsonProperty("uid")
    val uid: kotlin.collections.List<kotlin.String>? = null,
    @Schema(example = "null", description = "Input for the execution")
    @get:JsonProperty("input")
    val input: kotlin.String? = null,
) {}
