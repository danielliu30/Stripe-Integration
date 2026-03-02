package com.ender.takehome.leasing

import com.ender.takehome.TestFixtures
import com.ender.takehome.config.JwtAuthenticationFilter
import com.ender.takehome.config.JwtService
import com.ender.takehome.dto.request.CreateLeaseRequest
import com.ender.takehome.dto.response.CursorPage
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import com.ender.takehome.model.UserRole
import java.math.BigDecimal
import java.time.LocalDate

@WebMvcTest(LeaseApi::class)
@Import(LeaseApiTest.TestSecurityConfig::class)
class LeaseApiTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var leaseModule: LeaseModule

    @Autowired
    private lateinit var jwtService: JwtService

    @TestConfiguration
    @EnableMethodSecurity
    class TestSecurityConfig {
        @Bean
        fun jwtService(): JwtService = JwtService(
            secret = "test-jwt-secret-key-that-is-at-least-256-bits-long-for-hmac-sha256",
            expirationMs = 86400000L,
        )

        @Bean
        fun jwtAuthenticationFilter(jwtService: JwtService) = JwtAuthenticationFilter(jwtService)

        @Bean
        fun securityFilterChain(http: HttpSecurity, jwtFilter: JwtAuthenticationFilter): SecurityFilterChain = http
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests {
                it.requestMatchers("/api/auth/**").permitAll()
                    .anyRequest().authenticated()
            }
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter::class.java)
            .build()

        @Bean
        fun leaseModule(): LeaseModule = mockk()
    }

    private val lease = TestFixtures.lease()

    private fun pmToken(): String = jwtService.generateToken(
        userId = 1L, email = "pm@test.com", role = UserRole.PROPERTY_MANAGER, tenantId = null, pmId = 1L
    )

    @Test
    fun `GET leases returns cursor page for authenticated PM`() {
        every { leaseModule.getAll(null, any()) } returns CursorPage(listOf(lease), hasMore = false)

        mockMvc.get("/api/leases") {
            header("Authorization", "Bearer ${pmToken()}")
        }.andExpect {
            status { isOk() }
            jsonPath("$.content[0].rentAmount") { value(2000.0) }
            jsonPath("$.content[0].status") { value("ACTIVE") }
            jsonPath("$.hasMore") { value(false) }
        }
    }

    @Test
    fun `POST leases creates a new lease for PM`() {
        val request = CreateLeaseRequest(
            tenantId = 1,
            unitId = 1,
            rentAmount = BigDecimal("2500.00"),
            startDate = LocalDate.of(2025, 1, 1),
            endDate = LocalDate.of(2026, 1, 1),
        )

        every { leaseModule.create(any()) } returns lease

        mockMvc.post("/api/leases") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(request)
            header("Authorization", "Bearer ${pmToken()}")
        }.andExpect {
            status { isCreated() }
            jsonPath("$.status") { value("ACTIVE") }
        }
    }

    @Test
    fun `GET leases returns 403 for unauthenticated request`() {
        mockMvc.get("/api/leases")
            .andExpect {
                status { isForbidden() }
            }
    }
}
