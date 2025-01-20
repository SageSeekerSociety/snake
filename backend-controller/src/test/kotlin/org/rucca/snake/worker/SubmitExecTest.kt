package org.rucca.snake.controller

import org.junit.jupiter.api.MethodOrderer.OrderAnnotation
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@AutoConfigureMockMvc
@TestMethodOrder(OrderAnnotation::class)
class SubmitExecTest @Autowired constructor(private val mockMvc: MockMvc) {
    @Test
    @Order(10)
    fun testSubmit() {
        val request =
            MockMvcRequestBuilders.multipart("/submit").file("file", "test file".toByteArray())
        mockMvc.perform(request).andExpect(status().isNotImplemented)
    }

    @Test
    @Order(20)
    fun testExec() {
        val request =
            MockMvcRequestBuilders.post("/exec")
                .contentType("application/json")
                .content("""{"uid": ["1", "2", "3"], "input": "test input"}""")
        mockMvc.perform(request).andExpect(status().isNotImplemented)
    }
}
