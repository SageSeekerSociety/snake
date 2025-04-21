package org.rucca.snake.common.constants

object AmqpConstants {
    // Header key used to identify the message type
    const val HEADER_MESSAGE_TYPE = "messageType"

    // Values for the messageType header
    const val MESSAGE_TYPE_COMPILE = "CompilationRequest"
    const val MESSAGE_TYPE_EXECUTE = "ExecutionRequest"

    const val MESSAGE_TYPE_COMPILE_RESULT = "CompilationResult";
    const val MESSAGE_TYPE_EXECUTE_RESULT = "ExecutionResult";

    // Add other constants like queue names, exchange names if needed here,
    // although getting them from configuration (@Value) is often preferred.
    // const val QUEUE_COMPILE_TASKS = "oj.compile.tasks"
    // const val QUEUE_EXECUTE_TASKS = "oj.execute.tasks"
}
