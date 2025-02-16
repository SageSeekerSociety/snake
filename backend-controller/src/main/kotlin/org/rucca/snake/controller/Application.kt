package org.rucca.snake.controller

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.ComponentScan

@SpringBootApplication
@ComponentScan(basePackages = ["org.rucca.snake.controller", "org.rucca.cheese.auth"])
class Application

fun main(args: Array<String>) {
    runApplication<Application>(*args)
}
