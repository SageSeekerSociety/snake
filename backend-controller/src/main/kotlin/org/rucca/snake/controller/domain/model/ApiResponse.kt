package org.rucca.snake.controller.domain.model

import com.fasterxml.jackson.annotation.JsonInclude

@JsonInclude(JsonInclude.Include.NON_EMPTY)
data class ApiError(val type: String, val details: Any? = null)

sealed interface ApiResponse {
    val code: Int
    val message: String

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    data class Success<T>(
        override val code: Int = 200,
        override val message: String = "Success",
        val data: T? = null,
    ) : ApiResponse

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    data class Error(
        override val code: Int = 500,
        override val message: String,
        val error: ApiError? = null,
    ) : ApiResponse
}

typealias ErrorResponse = ApiResponse.Error
