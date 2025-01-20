package org.rucca.snake.worker.model

import com.fasterxml.jackson.annotation.JsonProperty
import io.swagger.v3.oas.annotations.media.Schema
import javax.validation.Valid

/**
 * @param success
 * @param &#x60;data&#x60;
 */
data class ExecPost200ResponseDTO(
    @Schema(example = "null", description = "")
    @get:JsonProperty("success")
    val success: kotlin.Boolean? = null,
    @field:Valid
    @Schema(example = "null", description = "")
    @get:JsonProperty("data")
    val `data`: kotlin.collections.List<ExecPost200ResponseDataInnerDTO>? = null,
) {}
