package org.rucca.snake.worker.model

import java.util.Objects
import com.fasterxml.jackson.annotation.JsonProperty
import org.rucca.snake.worker.model.ApiExecPost200ResponseDataInnerDTO
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
 * @param success 
 * @param &#x60;data&#x60; 
 */
data class ApiExecPost200ResponseDTO(

    @Schema(example = "null", description = "")
    @get:JsonProperty("success") val success: kotlin.Boolean? = null,

    @field:Valid
    @Schema(example = "null", description = "")
    @get:JsonProperty("data") val `data`: kotlin.collections.List<ApiExecPost200ResponseDataInnerDTO>? = null
    ) {

}

