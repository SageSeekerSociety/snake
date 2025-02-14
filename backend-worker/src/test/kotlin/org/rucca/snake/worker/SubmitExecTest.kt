package org.rucca.snake.worker

import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.*
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation
import org.rucca.cheese.auth.utils.UserCreatorService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
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
    fun testSubmit1WithError() {
        val src =
            """
            #include <iostream>
            
            int main() {
                std::cout << "Hello world!" << std::endl
                return 0;
            }
        """
                .trimIndent()
        val request =
            MockMvcRequestBuilders.multipart("/submit")
                .file("src", src.toByteArray())
                .header("Authorization", "Bearer $token")
        mockMvc
            .perform(request)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.message").value("OK"))
            .andExpect(jsonPath("$.data.success").value(false))
            .andExpect(jsonPath("$.data.diagnose", containsString("error")))
    }

    @Test
    @Order(20)
    fun testSubmit1WithWarning() {
        val src =
            """
            #include <iostream>
            #warning "This is a warning."
            
            int main() {
                std::cout << "Hello world!" << std::endl;
                return 0;
            }
        """
                .trimIndent()
        val request =
            MockMvcRequestBuilders.multipart("/submit")
                .file("src", src.toByteArray())
                .header("Authorization", "Bearer $token")
        mockMvc
            .perform(request)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.success").value(true))
            .andExpect(jsonPath("$.data.diagnose", containsString("warning")))
    }

    @Test
    @Order(30)
    fun testSubmit1NoWarning() {
        val src =
            """
            #include <iostream>
            
            int main() {
                std::cout << "Hello world!" << std::endl;
                return 0;
            }
        """
                .trimIndent()
        val request =
            MockMvcRequestBuilders.multipart("/submit")
                .file("src", src.toByteArray())
                .header("Authorization", "Bearer $token")
        mockMvc
            .perform(request)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.success").value(true))
            .andExpect(jsonPath("$.data.diagnose").value(""))
    }

    @Test
    @Order(100)
    fun testExecSingleSuccess() {
        val request =
            MockMvcRequestBuilders.post("/exec")
                .contentType("application/json")
                .content("""{"userIds": [${user.userId}], "input": "test input"}""")
                .header("Authorization", "Bearer $token")
        mockMvc
            .perform(request)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.message").value("OK"))
            .andExpect(jsonPath("$.data[0].output").value("Hello world!\n"))
            .andExpect(jsonPath("$.data[0].sandbox").isString)
            .andExpect(jsonPath("$.data[0].error").value(null))
    }

    @Test
    @Order(110)
    fun testExecMultipleSuccess() {
        val rep = 1000
        val userIds = IntRange(1, rep).map { user.userId }
        val request =
            MockMvcRequestBuilders.post("/exec")
                .contentType("application/json")
                .content("""{"userIds": $userIds, "input": "test input"}""")
                .header("Authorization", "Bearer $token")
        mockMvc
            .perform(request)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.message").value("OK"))
            .andExpect(jsonPath("$.data[0].output").value("Hello world!\n"))
            .andExpect(jsonPath("$.data[0].sandbox").isString)
            .andExpect(jsonPath("$.data[0].error").value(null))
            .andExpect(jsonPath("$.data[${rep - 1}].output").value("Hello world!\n"))
            .andExpect(jsonPath("$.data[${rep - 1}].sandbox").isString)
            .andExpect(jsonPath("$.data[${rep - 1}].error").value(null))
    }

    @Test
    @Order(120)
    fun testSubmit1Wrong() {
        val src =
            """
            #include <iostream>
            
            int main() {
                std::cout << "Hello world!" << std::endl;
                return 1;
            }
        """
                .trimIndent()
        val request =
            MockMvcRequestBuilders.multipart("/submit")
                .file("src", src.toByteArray())
                .header("Authorization", "Bearer $token")
        mockMvc.perform(request).andExpect(status().isOk)
    }

    @Test
    @Order(130)
    fun testExecSingleFailed() {
        val request =
            MockMvcRequestBuilders.post("/exec")
                .contentType("application/json")
                .content("""{"userIds": [${user.userId}], "input": "test input"}""")
                .header("Authorization", "Bearer $token")
        mockMvc
            .perform(request)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.message").value("OK"))
            .andExpect(jsonPath("$.data[0].output").value("Hello world!\n"))
            .andExpect(jsonPath("$.data[0].sandbox").isString)
            .andExpect(jsonPath("$.data[0].error.error.name").value("ExecutionError"))
            .andExpect(jsonPath("$.data[0].error.error.data.exitCode").value(1))
    }

    @Test
    @Order(140)
    fun testExecMultipleFailed() {
        val rep = 1000
        val userIds = IntRange(1, rep).map { user.userId }
        val request =
            MockMvcRequestBuilders.post("/exec")
                .contentType("application/json")
                .content("""{"userIds": $userIds, "input": "test input"}""")
                .header("Authorization", "Bearer $token")
        mockMvc
            .perform(request)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.message").value("OK"))
            .andExpect(jsonPath("$.data[0].output").value("Hello world!\n"))
            .andExpect(jsonPath("$.data[0].sandbox").isString)
            .andExpect(jsonPath("$.data[0].error.error.name").value("ExecutionError"))
            .andExpect(jsonPath("$.data[0].error.error.data.exitCode").value(1))
            .andExpect(jsonPath("$.data[${rep - 1}].output").value("Hello world!\n"))
            .andExpect(jsonPath("$.data[${rep - 1}].sandbox").isString)
            .andExpect(jsonPath("$.data[${rep - 1}].error.error.name").value("ExecutionError"))
            .andExpect(jsonPath("$.data[${rep - 1}].error.error.data.exitCode").value(1))
    }
}
