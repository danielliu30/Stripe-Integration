package com.ender.takehome

import com.ender.takehome.ledger.LedgerDataAccess
import com.ender.takehome.model.Card
import com.ender.takehome.model.PaymentStatus
import com.ender.takehome.model.RentCharge
import com.ender.takehome.model.Tenant
import com.ender.takehome.stripe.SetupIntentSucceeded
import com.ender.takehome.stripe.StripeCardDetails
import com.ender.takehome.stripe.StripeChargeResult
import com.ender.takehome.stripe.StripePaymentService
import com.ender.takehome.stripe.StripeService
import com.ender.takehome.stripe.UnhandledStripeWebhookEvent
import com.ender.takehome.tenant.TenantDataAccess
import com.stripe.exception.ApiConnectionException
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
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
import java.math.BigDecimal
import java.time.LocalDate
import java.util.ArrayDeque
import java.util.UUID

/**
 * Exercises the full saved-card requirement: one Stripe customer can hold several cards
 * (each attached by a signed setup webhook), and the tenant picks which one to pay with.
 */
@Tag("integration")
@SpringBootTest(properties = [
    "worker.enabled=false",
    "spring.datasource.url=jdbc:h2:mem:multi-card-payment;DB_CLOSE_DELAY=-1;MODE=MYSQL;DATABASE_TO_LOWER=TRUE",
])
@AutoConfigureMockMvc
@Import(MultiCardPaymentIntegrationTest.Config::class)
@Transactional
class MultiCardPaymentIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var tenantDataAccess: TenantDataAccess

    @Autowired
    private lateinit var ledgerDataAccess: LedgerDataAccess

    @Autowired
    private lateinit var stripeService: FakeStripeService

    @BeforeEach
    fun resetStripeService() {
        stripeService.reset()
    }

    @Test
    fun `one Stripe customer saves multiple cards and pays with the selected one`() {
        val aliceToken = login("alice.johnson@email.com")

        // Two card saves through the real path: checkout session + setup webhook per card.
        val firstCard = saveCardViaStripe(aliceToken, "pm_alice_visa", StripeCardDetails("visa", "4242", 12, 2030))
        val secondCard = saveCardViaStripe(aliceToken, "pm_alice_mc", StripeCardDetails("mastercard", "5555", 6, 2031))

        mockMvc.get("/api/cards") {
            header("Authorization", "Bearer $aliceToken")
        }.andExpect {
            status { isOk() }
            jsonPath("$.content.length()") { value(2) }
        }

        val charge = ledgerDataAccess.saveCharge(
            RentCharge(leaseId = 1L, amount = BigDecimal("25.00"), dueDate = LocalDate.of(2099, 4, 1))
        )

        // Pay with the second card; only that PaymentMethod should reach Stripe.
        val response = mockMvc.post("/api/rent-charges/${charge.id}/pay") {
            header("Authorization", "Bearer $aliceToken")
            header("Idempotency-Key", "multi-card-${UUID.randomUUID()}")
            contentType = MediaType.APPLICATION_JSON
            content = """{"cardId":${secondCard.id}}"""
        }.andExpect {
            status { isAccepted() }
            jsonPath("$.status") { value("SUCCEEDED") }
            jsonPath("$.card.id") { value(secondCard.id.toInt()) }
            jsonPath("$.card.last4") { value("5555") }
        }.andReturn().response.contentAsString

        assertEquals(secondCard.id, objectMapper.readTree(response).get("card").get("id").asLong())
        assertEquals(listOf("pm_alice_mc"), stripeService.chargedPaymentMethodIds)
        assertEquals(listOf("cus_alice_multi"), stripeService.chargedCustomerIds)
        assertEquals(1, stripeService.chargeCalls)
        assertEquals("PAID", ledgerDataAccess.findChargeById(charge.id)?.status?.name)
        assertEquals(firstCard.tenantId, secondCard.tenantId)
    }

    @Test
    fun `tenant cannot pay with a card saved under another tenant's Stripe customer`() {
        val aliceToken = login("alice.johnson@email.com")
        val aliceCard = saveCardViaStripe(aliceToken, "pm_alice_only", StripeCardDetails("visa", "4242", 12, 2030))

        val bob = requireNotNull(tenantDataAccess.findById(2L))
        tenantDataAccess.save(bob.copy(stripeCustomerId = "cus_bob"))
        val bobCharge = ledgerDataAccess.saveCharge(
            RentCharge(leaseId = 2L, amount = BigDecimal("25.00"), dueDate = LocalDate.of(2099, 5, 1))
        )
        val bobToken = login("bob.smith@email.com")

        mockMvc.post("/api/rent-charges/${bobCharge.id}/pay") {
            header("Authorization", "Bearer $bobToken")
            header("Idempotency-Key", "cross-tenant-${UUID.randomUUID()}")
            contentType = MediaType.APPLICATION_JSON
            content = """{"cardId":${aliceCard.id}}"""
        }.andExpect {
            status { isNotFound() }
        }

        assertEquals(0, stripeService.chargeCalls)
        assertEquals(0, ledgerDataAccess.findPaymentsByRentChargeIdCursor(bobCharge.id, null, 10).size)
    }

    /** Saves a card the way production does: checkout session, then Stripe's setup webhook. */
    private fun saveCardViaStripe(token: String, paymentMethodId: String, details: StripeCardDetails): Card {
        mockMvc.post("/api/cards/checkout-session") {
            header("Authorization", "Bearer $token")
        }.andExpect { status { isOk() } }

        stripeService.setupEvents.addLast(SetupIntentSucceeded("cus_alice_multi", paymentMethodId))
        stripeService.cardDetails[paymentMethodId] = details
        mockMvc.post("/api/webhooks/stripe") {
            header("Stripe-Signature", "valid-signature")
            contentType = MediaType.APPLICATION_JSON
            content = "{}"
        }.andExpect { status { isOk() } }

        val cards = mockMvc.get("/api/cards") {
            header("Authorization", "Bearer $token")
        }.andReturn().response.contentAsString
        val content = objectMapper.readTree(cards).get("content")
        val saved = content.first { it.get("last4").asText() == details.last4 }
        return Card(
            id = saved.get("id").asLong(),
            tenantId = 1L,
            stripePaymentMethodId = paymentMethodId,
            brand = saved.get("brand").asText(),
            last4 = details.last4,
            expMonth = details.expMonth,
            expYear = details.expYear,
        )
    }

    private fun login(email: String): String {
        val response = mockMvc.post("/api/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"$email","password":"password"}"""
        }.andReturn()
        return objectMapper.readTree(response.response.contentAsString).get("token").asText()
    }

    @TestConfiguration(proxyBeanMethods = false)
    class Config {
        @Bean
        @Primary
        fun fakeStripeService() = FakeStripeService()
    }

    /** Fakes both Stripe boundaries: card setup/webhooks and off-session charging. */
    class FakeStripeService : StripeService, StripePaymentService {
        var chargeCalls = 0
        val setupEvents = ArrayDeque<SetupIntentSucceeded>()
        val cardDetails = mutableMapOf<String, StripeCardDetails>()
        val checkoutCustomerIds = mutableListOf<String>()
        val chargedPaymentMethodIds = mutableListOf<String>()
        val chargedCustomerIds = mutableListOf<String>()

        fun reset() {
            chargeCalls = 0
            setupEvents.clear()
            cardDetails.clear()
            checkoutCustomerIds.clear()
            chargedPaymentMethodIds.clear()
            chargedCustomerIds.clear()
        }

        override fun createCustomer(tenant: Tenant): String = "cus_alice_multi"

        override fun createSetupCheckoutSession(customerId: String): String {
            checkoutCustomerIds += customerId
            return "https://checkout.stripe.test/session"
        }

        override fun parseWebhookEvent(payload: String, signature: String) =
            setupEvents.pollFirst() ?: UnhandledStripeWebhookEvent

        override fun getCardDetails(paymentMethodId: String): StripeCardDetails =
            cardDetails[paymentMethodId]
                ?: throw ApiConnectionException("Unknown payment method $paymentMethodId")

        override fun detachPaymentMethod(paymentMethodId: String) {}

        override fun chargeCard(
            customerId: String,
            paymentMethodId: String,
            amount: BigDecimal,
            idempotencyKey: String,
            metadata: Map<String, String>,
        ): StripeChargeResult {
            chargeCalls++
            chargedCustomerIds += customerId
            chargedPaymentMethodIds += paymentMethodId
            return StripeChargeResult("pi_$idempotencyKey", PaymentStatus.SUCCEEDED, null)
        }
    }
}
