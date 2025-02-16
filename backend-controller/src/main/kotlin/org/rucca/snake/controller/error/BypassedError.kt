package org.rucca.snake.controller.error

import com.fasterxml.jackson.databind.JsonNode
import org.springframework.http.HttpStatusCode

class BypassedError(val statusCode: HttpStatusCode, val json: JsonNode) :
    Exception("${statusCode.value()} - ${json.toPrettyString()}")
