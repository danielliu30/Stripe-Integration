package com.ender.takehome

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post

@Tag("integration")
@SpringBootTest(properties = ["worker.enabled=false"])
@AutoConfigureMockMvc
class AuthIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Test
    fun `wrong password returns structured unauthorized response`() {
        mockMvc.post("/api/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"alice.johnson@email.com","password":"wrong"}"""
        }.andExpect {
            status { isUnauthorized() }
            jsonPath("$.status") { value(401) }
            jsonPath("$.error") { value("Unauthorized") }
            jsonPath("$.message") { value("Invalid credentials") }
        }
    }

    @Test
    fun `unknown email returns same unauthorized response`() {
        mockMvc.post("/api/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"nobody@example.com","password":"password"}"""
        }.andExpect {
            status { isUnauthorized() }
            jsonPath("$.message") { value("Invalid credentials") }
        }
    }

    @Test
    fun `authenticated tenant remains forbidden from manager endpoint`() {
        val login = mockMvc.post("/api/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"alice.johnson@email.com","password":"password"}"""
        }.andReturn().response.contentAsString
        val token = objectMapper.readTree(login).get("token").asText()

        mockMvc.post("/api/rent-charges/generate") {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            content = "{}"
        }.andExpect { status { isForbidden() } }
    }
}
