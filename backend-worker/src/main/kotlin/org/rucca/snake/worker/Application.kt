package org.rucca.snake.worker

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.ComponentScan

@SpringBootApplication
@ComponentScan(basePackages = ["org.rucca.snake.worker", "org.rucca.cheese.auth"])
class Application

fun main(args: Array<String>) {
    runApplication<Application>(*args)
}
