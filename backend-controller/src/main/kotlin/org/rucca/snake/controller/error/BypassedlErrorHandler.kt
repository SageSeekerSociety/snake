/*
 *  Description: This file implements GlobalErrorHandler class.
 *               It handles all exceptions thrown by controllers.
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package org.rucca.snake.controller.error

import com.fasterxml.jackson.databind.JsonNode
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.ResponseBody

@ControllerAdvice
class BypassedlErrorHandler {
    @ExceptionHandler(BypassedError::class)
    @ResponseBody
    fun handleBaseError(e: BypassedError): ResponseEntity<JsonNode> {
        return ResponseEntity.status(e.statusCode).body(e.json)
    }
}
