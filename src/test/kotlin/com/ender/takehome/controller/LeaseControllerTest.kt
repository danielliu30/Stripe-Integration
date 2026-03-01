package com.ender.takehome.controller

import com.ender.takehome.TestFixtures
import com.ender.takehome.dto.request.CreateLeaseRequest
import com.ender.takehome.service.LeaseService
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.math.BigDecimal
import java.time.LocalDate

@WebMvcTest(LeaseController::class)
class LeaseControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var leaseService: LeaseService

    @TestConfiguration
    class Config {
        @Bean
        fun leaseService(): LeaseService = mockk()
    }

    private val pm = TestFixtures.propertyManager()
    private val property = TestFixtures.property(pm)
    private val unit = TestFixtures.unit(property)
    private val tenant = TestFixtures.tenant()
    private val lease = TestFixtures.lease(tenant, unit)

    @Test
    fun `GET leases returns list of leases`() {
        every { leaseService.getAll() } returns listOf(lease)

        mockMvc.get("/api/leases")
            .andExpect {
                status { isOk() }
                jsonPath("$[0].rentAmount") { value(2000.0) }
                jsonPath("$[0].status") { value("ACTIVE") }
            }
    }

    @Test
    fun `POST leases creates a new lease`() {
        val request = CreateLeaseRequest(
            tenantId = 1,
            unitId = 1,
            rentAmount = BigDecimal("2500.00"),
            startDate = LocalDate.of(2025, 1, 1),
            endDate = LocalDate.of(2026, 1, 1),
        )

        every { leaseService.create(any()) } returns lease

        mockMvc.post("/api/leases") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(request)
        }.andExpect {
            status { isCreated() }
            jsonPath("$.status") { value("ACTIVE") }
        }
    }
}
