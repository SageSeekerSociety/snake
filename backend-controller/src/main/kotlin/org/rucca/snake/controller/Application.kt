package org.rucca.snake.controller

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.boot.runApplication
import org.springframework.context.annotation.ComponentScan
import org.springframework.data.jpa.repository.config.EnableJpaRepositories

@SpringBootApplication
@ComponentScan(
    basePackages = ["org.rucca.snake.common", "org.rucca.snake.controller", "org.rucca.cheese.auth"]
)
@EntityScan(basePackages = ["org.rucca.snake.common", "org.rucca.snake.controller"])
@EnableJpaRepositories(basePackages = ["org.rucca.snake.common", "org.rucca.snake.controller"])
class Application

fun main(args: Array<String>) {
    runApplication<Application>(*args)
}
