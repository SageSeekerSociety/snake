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
 * @param sid 
 * @param output 
 * @param result 
 * @param error 
 */
data class ApiExecPost200ResponseDataInnerDTO(

    @Schema(example = "null", description = "")
    @get:JsonProperty("sid") val sid: kotlin.String? = null,

    @Schema(example = "null", description = "")
    @get:JsonProperty("output") val output: kotlin.String? = null,

    @field:Valid
    @Schema(example = "null", description = "")
    @get:JsonProperty("result") val result: kotlin.Any? = null,

    @Schema(example = "null", description = "")
    @get:JsonProperty("error") val error: kotlin.String? = null
    ) {

}

