package org.rucca.snake.controller.model

import com.fasterxml.jackson.annotation.JsonProperty
import io.swagger.v3.oas.annotations.media.Schema

/**
 * @param userIds Array of user IDs
 * @param input Input for the execution
 */
data class ExecPostRequestDTO(
    @Schema(example = "null", required = true, description = "Array of user IDs")
    @get:JsonProperty("userIds", required = true)
    val userIds: kotlin.collections.List<kotlin.Long>,
    @Schema(example = "null", required = true, description = "Input for the execution")
    @get:JsonProperty("input", required = true)
    val input: kotlin.String,
) {}
