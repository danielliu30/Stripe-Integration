package com.ender.takehome

import com.ender.takehome.model.Tenant
import com.ender.takehome.stripe.StripeService
import com.ender.takehome.tenant.TenantDataAccess
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import org.springframework.transaction.annotation.Transactional

@Tag("integration")
@SpringBootTest(properties = ["worker.enabled=false"])
@AutoConfigureMockMvc
@Import(CardCheckoutSessionIntegrationTest.Config::class)
@Transactional
class CardCheckoutSessionIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var tenantDataAccess: TenantDataAccess

    @Autowired
    private lateinit var stripeService: FakeStripeService

    @Test
    fun `creates Stripe customer once and returns checkout redirect URL`() {
        val login = mockMvc.post("/api/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"alice.johnson@email.com","password":"password"}"""
        }.andReturn()
        val token = objectMapper.readTree(login.response.contentAsString).get("token").asText()

        repeat(2) {
            mockMvc.post("/api/cards/checkout-session") {
                header("Authorization", "Bearer $token")
            }.andExpect {
                status { isOk() }
                jsonPath("$.redirectUrl") { value("https://checkout.stripe.test/session") }
            }
        }

        assertEquals("cus_test", tenantDataAccess.findById(1L)?.stripeCustomerId)
        assertEquals(1, stripeService.createCustomerCalls)
        assertEquals(listOf("cus_test", "cus_test"), stripeService.checkoutCustomerIds)
    }

    @TestConfiguration(proxyBeanMethods = false)
    class Config {
        @Bean
        @Primary
        fun fakeStripeService() = FakeStripeService()
    }

    class FakeStripeService : StripeService {
        var createCustomerCalls = 0
        val checkoutCustomerIds = mutableListOf<String>()

        override fun createCustomer(tenant: Tenant): String {
            createCustomerCalls++
            return "cus_test"
        }

        override fun createSetupCheckoutSession(customerId: String): String {
            checkoutCustomerIds += customerId
            return "https://checkout.stripe.test/session"
        }
    }
}
