package com.ender.takehome

import com.ender.takehome.model.Tenant
import com.ender.takehome.stripe.SetupIntentSucceeded
import com.ender.takehome.stripe.StripeCardDetails
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
import org.springframework.test.web.servlet.get
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

    @Test
    fun `setup webhook persists card once and exposes it to tenant`() {
        val login = mockMvc.post("/api/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"alice.johnson@email.com","password":"password"}"""
        }.andReturn()
        val token = objectMapper.readTree(login.response.contentAsString).get("token").asText()
        mockMvc.post("/api/cards/checkout-session") {
            header("Authorization", "Bearer $token")
        }.andExpect { status { isOk() } }
        stripeService.setupEvent = SetupIntentSucceeded("cus_test", "pm_test")

        repeat(2) {
            mockMvc.post("/api/webhooks/stripe") {
                header("Stripe-Signature", "valid-signature")
                contentType = MediaType.APPLICATION_JSON
                content = "{}"
            }.andExpect { status { isOk() } }
        }

        mockMvc.get("/api/cards") {
            header("Authorization", "Bearer $token")
        }.andExpect {
            status { isOk() }
            jsonPath("$.content.length()") { value(1) }
            jsonPath("$.content[0].brand") { value("visa") }
            jsonPath("$.content[0].last4") { value("4242") }
        }
        assertEquals(1, stripeService.cardDetailsCalls)
    }

    @Test
    fun `setup webhook rejects missing signature`() {
        mockMvc.post("/api/webhooks/stripe") {
            contentType = MediaType.APPLICATION_JSON
            content = "{}"
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.message") { value("Missing Stripe-Signature header") }
        }
    }

    @Test
    fun `checkout return reports submitted card details without authentication`() {
        mockMvc.get("/api/checkout/return")
            .andExpect {
                status { isOk() }
                jsonPath("$.status") { value("success") }
                jsonPath("$.message") { value("Card details submitted. Your card will appear after confirmation.") }
            }
    }

    @Test
    fun `checkout return reports cancellation without authentication`() {
        mockMvc.get("/api/checkout/return") {
            param("status", "cancelled")
        }.andExpect {
            status { isOk() }
            jsonPath("$.status") { value("cancelled") }
            jsonPath("$.message") { value("Card setup was cancelled. No card was saved.") }
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    class Config {
        @Bean
        @Primary
        fun fakeStripeService() = FakeStripeService()
    }

    class FakeStripeService : StripeService {
        var createCustomerCalls = 0
        var cardDetailsCalls = 0
        var setupEvent: SetupIntentSucceeded? = null
        val checkoutCustomerIds = mutableListOf<String>()

        override fun createCustomer(tenant: Tenant): String {
            createCustomerCalls++
            return "cus_test"
        }

        override fun createSetupCheckoutSession(customerId: String): String {
            checkoutCustomerIds += customerId
            return "https://checkout.stripe.test/session"
        }

        override fun parseSetupIntentSucceeded(payload: String, signature: String): SetupIntentSucceeded? = setupEvent

        override fun getCardDetails(paymentMethodId: String): StripeCardDetails {
            cardDetailsCalls++
            return StripeCardDetails("visa", "4242", 12, 2030)
        }
    }
}
