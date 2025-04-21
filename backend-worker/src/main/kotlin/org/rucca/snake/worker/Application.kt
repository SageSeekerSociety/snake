package org.rucca.snake.worker

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.boot.runApplication
import org.springframework.context.annotation.ComponentScan
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@ComponentScan(basePackages = ["org.rucca.snake.common", "org.rucca.snake.worker"])
@EntityScan(basePackages = ["org.rucca.snake.common", "org.rucca.snake.worker"])
@EnableScheduling
@EnableJpaRepositories(basePackages = ["org.rucca.snake.common", "org.rucca.snake.worker"])
open class Application

fun main(args: Array<String>) {
    runApplication<Application>(*args)
}
