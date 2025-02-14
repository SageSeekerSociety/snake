package org.rucca.snake.worker

import org.junit.jupiter.api.*
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation
import org.rucca.cheese.auth.utils.UserCreatorService
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
class SubmitExecTest
@Autowired
constructor(private val mockMvc: MockMvc, private val userCreatorService: UserCreatorService) {
    private lateinit var user: UserCreatorService.CreateUserResponse
    private lateinit var token: String

    @BeforeAll
    fun prepare() {
        user = userCreatorService.createUser()
        token = userCreatorService.login(user.username, user.password)
    }

    @Test
    @Order(10)
    fun testSubmit() {
        val request =
            MockMvcRequestBuilders.multipart("/submit")
                .file("file", "test file".toByteArray())
                .header("Authorization", "Bearer $token")
        mockMvc.perform(request).andExpect(status().isNotImplemented)
    }

    @Test
    @Order(20)
    fun testExec() {
        val request =
            MockMvcRequestBuilders.post("/exec")
                .contentType("application/json")
                .content("""{"uid": ["1", "2", "3"], "input": "test input"}""")
                .header("Authorization", "Bearer $token")
        mockMvc.perform(request).andExpect(status().isNotImplemented)
    }
}
