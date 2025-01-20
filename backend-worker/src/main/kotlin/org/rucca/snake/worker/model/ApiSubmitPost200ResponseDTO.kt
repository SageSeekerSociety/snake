package org.rucca.snake.worker.model

import java.util.Objects
import com.fasterxml.jackson.annotation.JsonProperty
import org.rucca.snake.worker.model.ApiSubmitPost200ResponseCompileDTO
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
 * @param error 
 * @param compile 
 */
data class ApiSubmitPost200ResponseDTO(

    @Schema(example = "null", description = "")
    @get:JsonProperty("error") val error: kotlin.String? = null,

    @field:Valid
    @Schema(example = "null", description = "")
    @get:JsonProperty("compile") val compile: ApiSubmitPost200ResponseCompileDTO? = null
    ) {

}

