package com.ender.takehome

import com.fasterxml.jackson.databind.JsonNode
import org.awaitility.Awaitility
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.time.Duration

@Tag("integration")
class RentChargeGenerationIntegrationTest : IntegrationTestBase() {

    /**
     * The custom ObjectMapper bean serializes LocalDate as a JSON array [year,month,day].
     * This helper normalizes the date node to an ISO string for assertions.
     */
    private fun dueDateText(node: JsonNode): String =
        if (node.isArray) "%04d-%02d-%02d".format(node[0].asInt(), node[1].asInt(), node[2].asInt())
        else node.asText()

    private fun loginAsPm(): String {
        val result = mockMvc.post("/api/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(
                mapOf("email" to "admin@greenfieldproperties.com", "password" to "password")
            )
        }.andExpect {
            status { isOk() }
        }.andReturn()

        return objectMapper.readTree(result.response.contentAsString).get("token").asText()
    }

    @Test
    fun `POST generate rent charges creates new charges via background job`() {
        val token = loginAsPm()

        // Trigger rent charge generation for July
        mockMvc.post("/api/rent-charges/generate") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(mapOf("dueDate" to "2025-07-01"))
            header("Authorization", "Bearer $token")
        }.andExpect {
            status { isAccepted() }
        }

        // Wait for the worker to pick up the SQS message and process it
        Awaitility.await()
            .atMost(Duration.ofSeconds(10))
            .pollInterval(Duration.ofMillis(500))
            .untilAsserted {
                val result = mockMvc.get("/api/rent-charges") {
                    param("leaseId", "1")
                    header("Authorization", "Bearer $token")
                }.andExpect {
                    status { isOk() }
                }.andReturn()

                val content = objectMapper.readTree(result.response.contentAsString).get("content")

                // Seed data has 2 charges for lease 1 (May + June), July is new
                assertTrue(content.size() >= 3, "Expected at least 3 charges but found ${content.size()}")

                var foundJuly = false
                for (i in 0 until content.size()) {
                    val charge = content[i]
                    if (dueDateText(charge.get("dueDate")) == "2025-07-01") {
                        assertEquals("PENDING", charge.get("status").asText())
                        assertEquals(2500.0, charge.get("amount").asDouble())
                        foundJuly = true
                    }
                }
                assertTrue(foundJuly, "Expected a charge with dueDate 2025-07-01")
            }
    }

    @Test
    fun `generating rent charges twice for the same month is idempotent`() {
        val token = loginAsPm()

        // Enqueue the same job twice
        repeat(2) {
            mockMvc.post("/api/rent-charges/generate") {
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(mapOf("dueDate" to "2025-08-01"))
                header("Authorization", "Bearer $token")
            }.andExpect {
                status { isAccepted() }
            }
        }

        // Wait for both messages to be processed
        Awaitility.await()
            .atMost(Duration.ofSeconds(15))
            .pollInterval(Duration.ofMillis(500))
            .untilAsserted {
                val result = mockMvc.get("/api/rent-charges") {
                    param("leaseId", "1")
                    header("Authorization", "Bearer $token")
                }.andExpect {
                    status { isOk() }
                }.andReturn()

                val content = objectMapper.readTree(result.response.contentAsString).get("content")

                // Count August charges — should be exactly 1 despite two enqueue calls
                var augCount = 0
                for (i in 0 until content.size()) {
                    if (dueDateText(content[i].get("dueDate")) == "2025-08-01") augCount++
                }
                assertEquals(1, augCount, "Expected exactly 1 charge for 2025-08-01 but found $augCount")
            }
    }
}
