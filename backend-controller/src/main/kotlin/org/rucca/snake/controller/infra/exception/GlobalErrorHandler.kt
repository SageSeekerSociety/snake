/*
 *  Description: This file implements GlobalErrorHandler class.
 *               It handles all exceptions thrown by controllers.
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package org.rucca.snake.controller.infra.exception

import org.rucca.cheese.auth.error.BaseError
import org.rucca.snake.controller.domain.model.ApiError
import org.rucca.snake.controller.domain.model.ErrorResponse
import org.slf4j.LoggerFactory
import org.springframework.amqp.AmqpException
import org.springframework.dao.DataAccessException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.HttpMediaTypeNotSupportedException
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.multipart.MaxUploadSizeExceededException
import org.springframework.web.servlet.NoHandlerFoundException

@RestControllerAdvice // Handles exceptions across all @RestController beans
class GlobalExceptionHandler {

    private val logger = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

    // --- 4xx Client Errors ---

    // Handle @Valid validation failures on request bodies/params
    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidationExceptions(
        ex: MethodArgumentNotValidException
    ): ResponseEntity<ErrorResponse> {
        val errors =
            ex.bindingResult.fieldErrors.associate {
                it.field to (it.defaultMessage ?: "Invalid value")
            }
        val detailMessage = errors.entries.joinToString(", ") { "${it.key}: ${it.value}" }
        logger.warn("Validation failed: {}", detailMessage)
        return createErrorResponse(
            status = HttpStatus.BAD_REQUEST,
            code = 400, // General validation code
            message = "Validation failed. Please check your input.",
            errorType = "VALIDATION_ERROR",
            details = errors, // Include map of field errors
        )
    }

    // Handle missing required request parameters (@RequestParam)
    @ExceptionHandler(MissingServletRequestParameterException::class)
    fun handleMissingServletRequestParameterException(
        ex: MissingServletRequestParameterException
    ): ResponseEntity<ErrorResponse> {
        val message = "Required parameter '${ex.parameterName}' is missing."
        logger.warn(message)
        return createErrorResponse(
            status = HttpStatus.BAD_REQUEST,
            code = 400,
            message = message,
            errorType = "MISSING_PARAMETER",
            details =
                mapOf("parameterName" to ex.parameterName, "parameterType" to ex.parameterType),
        )
    }

    // Handle type mismatches for path variables or request parameters (@PathVariable,
    // @RequestParam)
    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun handleMethodArgumentTypeMismatchException(
        ex: MethodArgumentTypeMismatchException
    ): ResponseEntity<ErrorResponse> {
        val requiredType = ex.requiredType?.simpleName ?: "unknown"
        val message =
            "Parameter '${ex.name}' should be of type '$requiredType' but received value '${ex.value}'."
        logger.warn(message)
        return createErrorResponse(
            status = HttpStatus.BAD_REQUEST,
            code = 400,
            message = "Invalid parameter type provided.",
            errorType = "TYPE_MISMATCH",
            details =
                mapOf(
                    "parameterName" to ex.name,
                    "requiredType" to requiredType,
                    "value" to ex.value,
                ),
        )
    }

    // Handle errors during request body deserialization (e.g., invalid JSON format)
    // HttpMessageNotReadableException is more specific than HttpMessageConversionException
    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleHttpMessageNotReadableException(
        ex: HttpMessageNotReadableException
    ): ResponseEntity<ErrorResponse> {
        val rootCause = ex.mostSpecificCause.message ?: "Invalid request body format."
        logger.warn("Failed to read request body: {}", rootCause)
        return createErrorResponse(
            status = HttpStatus.BAD_REQUEST,
            code = 400,
            message = "Invalid request body. Please ensure it is correctly formatted.",
            errorType = "INVALID_REQUEST_BODY",
            details = rootCause, // Provide underlying parser message if available
        )
    }

    // Handle unsupported media types (e.g., sending JSON to an endpoint expecting XML)
    @ExceptionHandler(HttpMediaTypeNotSupportedException::class)
    fun handleHttpMediaTypeNotSupportedException(
        ex: HttpMediaTypeNotSupportedException
    ): ResponseEntity<ErrorResponse> {
        val supportedTypes = ex.supportedMediaTypes.joinToString(", ") { it.toString() }
        val message =
            "Unsupported media type '${ex.contentType}'. Supported types are: $supportedTypes."
        logger.warn(message)
        return createErrorResponse(
            status = HttpStatus.UNSUPPORTED_MEDIA_TYPE, // 415
            code = 415,
            message = message,
            errorType = "UNSUPPORTED_MEDIA_TYPE",
            details = mapOf("received" to ex.contentType.toString(), "supported" to supportedTypes),
        )
    }

    // Handle unsupported HTTP methods (e.g., GET request to a POST endpoint)
    @ExceptionHandler(HttpRequestMethodNotSupportedException::class)
    fun handleHttpRequestMethodNotSupportedException(
        ex: HttpRequestMethodNotSupportedException
    ): ResponseEntity<ErrorResponse> {
        val supportedMethods = ex.supportedMethods?.joinToString(", ") ?: "N/A"
        val message =
            "HTTP method '${ex.method}' is not supported for this request. Supported methods are: $supportedMethods."
        logger.warn(message)
        return createErrorResponse(
            status = HttpStatus.METHOD_NOT_ALLOWED, // 405
            code = 405,
            message = message,
            errorType = "METHOD_NOT_ALLOWED",
            details = mapOf("received" to ex.method, "supported" to supportedMethods),
        )
    }

    // Handle file uploads exceeding configured limits
    @ExceptionHandler(MaxUploadSizeExceededException::class)
    fun handleMaxUploadSizeExceededException(
        ex: MaxUploadSizeExceededException
    ): ResponseEntity<ErrorResponse> {
        val limit = ex.maxUploadSize / (1024 * 1024) // Convert bytes to MB for user message
        val message = "Maximum upload size of ${limit}MB exceeded."
        logger.warn(message)
        return createErrorResponse(
            status = HttpStatus.PAYLOAD_TOO_LARGE, // 413
            code = 413,
            message = message,
            errorType = "PAYLOAD_TOO_LARGE",
            details = "Max upload size: ${ex.maxUploadSize} bytes",
        )
    }

    // Handle requests to non-existent endpoints (requires configuration in application.yml)
    // spring.mvc.throw-exception-if-no-handler-found=true
    // spring.web.resources.add-mappings=false
    @ExceptionHandler(NoHandlerFoundException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND) // Use annotation for status code
    fun handleNoHandlerFoundException(ex: NoHandlerFoundException): ErrorResponse {
        val message = "The requested resource '${ex.requestURL}' could not be found."
        logger.warn(message)
        return ErrorResponse(
            code = 404, // General Not Found code
            message = message,
            error =
                ApiError(
                    type = "NOT_FOUND",
                    details = "Path: ${ex.requestURL}, Method: ${ex.httpMethod}",
                ),
        )
    }

    // --- 5xx Server Errors ---

    // Handle database access errors (DataAccessException is Spring's abstraction)
    @ExceptionHandler(DataAccessException::class)
    fun handleDatabaseExceptions(ex: DataAccessException): ResponseEntity<ErrorResponse> {
        logger.error(
            "Database access error: {}",
            ex.mostSpecificCause.message,
            ex,
        ) // Log full stack trace
        return createErrorResponse(
            status = HttpStatus.INTERNAL_SERVER_ERROR,
            code = 500,
            message = "A database error occurred. Please try again later or contact support.",
            errorType = "DATABASE_ERROR",
            details = ex.mostSpecificCause.message ?: "Unknown database error",
        )
    }

    // Handle RabbitMQ communication errors
    @ExceptionHandler(AmqpException::class)
    fun handleAmqpExceptions(ex: AmqpException): ResponseEntity<ErrorResponse> {
        logger.error("AMQP error: {}", ex.cause?.message ?: ex.message, ex)
        return createErrorResponse(
            status = HttpStatus.INTERNAL_SERVER_ERROR,
            code = 500,
            message =
                "Failed to communicate with the messaging system. The operation might not have been processed.",
            errorType = "MESSAGING_ERROR",
            details = ex.cause?.message ?: ex.message ?: "Unknown AMQP error",
        )
    }

    @ExceptionHandler(BaseError::class)
    fun handleBaseError(ex: BaseError): ResponseEntity<ErrorResponse> {
        val status = ex.status
        logger.error(
            "Application specific error occurred [{}]: {}",
            status,
            ex.message,
            ex,
        ) // Log with status
        return createErrorResponse(
            status = status,
            code = status.value(),
            message = ex.message,
            errorType = ex.javaClass.simpleName.replace("Error", "").uppercase(),
            details = ex.data,
        )
    }

    // --- Generic Fallback Handler ---

    // Catch-all for any other unhandled exceptions
    @ExceptionHandler(Exception::class)
    fun handleGenericExceptions(ex: Exception): ResponseEntity<ErrorResponse> {
        logger.error(
            "An unexpected internal server error occurred: {}",
            ex.message,
            ex,
        ) // Always log stack trace for unexpected errors
        return createErrorResponse(
            status = HttpStatus.INTERNAL_SERVER_ERROR,
            code = 5000,
            message =
                "An unexpected internal server error occurred. Please contact support if the problem persists.",
            errorType = "INTERNAL_SERVER_ERROR",
            details = ex.message, // Provide basic message, avoid exposing sensitive details
        )
    }

    // --- Helper Function ---
    private fun createErrorResponse(
        status: HttpStatus,
        code: Int,
        message: String,
        errorType: String,
        details: Any? = null,
    ): ResponseEntity<ErrorResponse> {
        val errorResponse =
            ErrorResponse(
                code = code,
                message = message,
                error = ApiError(type = errorType, details = details),
            )
        return ResponseEntity(errorResponse, status)
    }
}
