package org.rucca.snake.worker.model

import java.util.Objects
import com.fasterxml.jackson.annotation.JsonProperty
import javax.validation.constraints.DecimalMax
import javax.validation.constraints.DecimalMin
import javax.validation.constraints.Email
import javax.validation.constraints.Max
import javax.validation.constraints.Min
import javax.validation.constraints.NotNull
import javax.validation.constraints.Pattern
import javax.validation.constraints.Size
import javax.validation.Valid
import io.swagger.v3.oas.annotations.media.Schema

/**
 * 
 * @param uid Array of user IDs
 * @param input Input for the execution
 */
data class ApiExecPostRequestDTO(

    @Schema(example = "null", description = "Array of user IDs")
    @get:JsonProperty("uid") val uid: kotlin.collections.List<kotlin.String>? = null,

    @Schema(example = "null", description = "Input for the execution")
    @get:JsonProperty("input") val input: kotlin.String? = null
    ) {

}

