package com.ender.takehome

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.transaction.annotation.Transactional

@Tag("integration")
@SpringBootTest(properties = ["worker.enabled=false"])
@AutoConfigureMockMvc
@Transactional
class PaymentHistoryIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Test
    fun `payment history scopes tenants and includes all manual payment types`() {
        val tenantToken = login("alice.johnson@email.com")

        mockMvc.get("/api/payments") {
            header("Authorization", "Bearer $tenantToken")
        }.andExpect {
            status { isOk() }
            jsonPath("$.content.length()") { value(1) }
            jsonPath("$.content[0].rentChargeId") { value(2) }
            jsonPath("$.content[0].paymentMethod") { value("CHECK") }
            jsonPath("$.content[0].status") { value("SUCCEEDED") }
            jsonPath("$.content[0].recordedBy") { value("admin@greenfieldproperties.com") }
            jsonPath("$.content[0].card") { doesNotExist() }
        }

        val propertyManagerToken = login("admin@greenfieldproperties.com")
        mockMvc.post("/api/payments") {
            header("Authorization", "Bearer $propertyManagerToken")
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "rentChargeId": 1,
                  "amount": 25.00,
                  "paymentMethod": "OTHER",
                  "notes": "Money order",
                  "recordedBy": "admin@greenfieldproperties.com"
                }
            """.trimIndent()
        }.andExpect { status { isCreated() } }

        mockMvc.get("/api/payments") {
            header("Authorization", "Bearer $propertyManagerToken")
        }.andExpect {
            status { isOk() }
            jsonPath("$.content.length()") { value(3) }
            jsonPath("$.content[0].paymentMethod") { value("CHECK") }
            jsonPath("$.content[1].paymentMethod") { value("CASH") }
            jsonPath("$.content[2].paymentMethod") { value("OTHER") }
            jsonPath("$.content[2].status") { value("SUCCEEDED") }
            jsonPath("$.content[2].recordedBy") { value("admin@greenfieldproperties.com") }
            jsonPath("$.hasMore") { value(false) }
        }
    }

    private fun login(email: String): String {
        val response = mockMvc.post("/api/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"$email","password":"password"}"""
        }.andReturn()
        return objectMapper.readTree(response.response.contentAsString).get("token").asText()
    }
}
