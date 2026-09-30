package com.ender.takehome

import com.ender.takehome.ledger.LedgerDataAccess
import com.ender.takehome.model.RentCharge
import com.ender.takehome.stripe.StripePaymentService
import com.ender.takehome.tenant.TenantDataAccess
import com.fasterxml.jackson.databind.ObjectMapper
import com.stripe.Stripe
import com.stripe.StripeClient
import com.stripe.param.PaymentIntentListParams
import com.stripe.param.PaymentMethodAttachParams
import com.stripe.param.PaymentMethodCreateParams
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@Tag("stripe")
@SpringBootTest(properties = [
    "worker.enabled=false",
    "spring.datasource.url=jdbc:h2:mem:real-stripe-card-setup;DB_CLOSE_DELAY=-1;MODE=MYSQL;DATABASE_TO_LOWER=TRUE",
])
@AutoConfigureMockMvc
class RealStripeCardSetupIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var tenantDataAccess: TenantDataAccess

    @Autowired
    private lateinit var ledgerDataAccess: LedgerDataAccess

    @Autowired
    private lateinit var stripePaymentService: StripePaymentService

    @Test
    fun `creates checkout session and persists real Stripe card`() {
        var customerId: String? = null
        try {
            val token = login("alice.johnson@email.com")
            val checkout = mockMvc.post("/api/cards/checkout-session") {
                header("Authorization", "Bearer $token")
            }.andExpect {
                status { isOk() }
                jsonPath("$.redirectUrl") { isString() }
            }.andReturn()
            val redirectUrl = objectMapper.readTree(checkout.response.contentAsString).get("redirectUrl").asText()
            assertTrue(redirectUrl.startsWith("https://checkout.stripe.com/"))

            customerId = requireNotNull(tenantDataAccess.findById(1L)?.stripeCustomerId)
            val paymentMethod = stripeClient.v1().paymentMethods().create(
                PaymentMethodCreateParams.builder()
                    .setType(PaymentMethodCreateParams.Type.CARD)
                    .setCard(PaymentMethodCreateParams.Token.builder().setToken("tok_visa").build())
                    .build()
            )
            stripeClient.v1().paymentMethods().attach(
                paymentMethod.id,
                PaymentMethodAttachParams.builder().setCustomer(customerId).build(),
            )
            postSetupWebhook(customerId, paymentMethod.id)

            val cards = mockMvc.get("/api/cards") {
                header("Authorization", "Bearer $token")
            }.andExpect {
                status { isOk() }
                jsonPath("$.content.length()") { value(1) }
                jsonPath("$.content[0].brand") { value("visa") }
                jsonPath("$.content[0].last4") { value("4242") }
            }.andReturn()
            val cardId = objectMapper.readTree(cards.response.contentAsString).get("content")[0].get("id").asLong()

            mockMvc.delete("/api/cards/$cardId") {
                header("Authorization", "Bearer $token")
            }.andExpect { status { isNoContent() } }
        } finally {
            customerId?.let { stripeClient.v1().customers().delete(it) }
        }
    }

    @Test
    fun `charges real Stripe card and verifies Stripe idempotency`() {
        var customerId: String? = null
        try {
            val token = login("bob.smith@email.com")
            mockMvc.post("/api/cards/checkout-session") {
                header("Authorization", "Bearer $token")
            }.andExpect { status { isOk() } }
            customerId = requireNotNull(tenantDataAccess.findById(2L)?.stripeCustomerId)
            val paymentMethod = stripeClient.v1().paymentMethods().create(
                PaymentMethodCreateParams.builder()
                    .setType(PaymentMethodCreateParams.Type.CARD)
                    .setCard(PaymentMethodCreateParams.Token.builder().setToken("tok_visa").build())
                    .build()
            )
            stripeClient.v1().paymentMethods().attach(
                paymentMethod.id,
                PaymentMethodAttachParams.builder().setCustomer(customerId).build(),
            )
            postSetupWebhook(customerId, paymentMethod.id)
            val cards = mockMvc.get("/api/cards") {
                header("Authorization", "Bearer $token")
            }.andReturn()
            val cardId = objectMapper.readTree(cards.response.contentAsString).get("content")[0].get("id").asLong()
            val charge = ledgerDataAccess.saveCharge(
                RentCharge(leaseId = 2L, amount = BigDecimal("1.00"), dueDate = LocalDate.of(2099, 1, 1))
            )
            val requestIdempotencyKey = "real-request-${UUID.randomUUID()}"
            val firstPayment = pay(token, charge.id, cardId, requestIdempotencyKey)
            val secondPayment = pay(token, charge.id, cardId, requestIdempotencyKey)
            assertEquals(
                objectMapper.readTree(firstPayment).get("id").asLong(),
                objectMapper.readTree(secondPayment).get("id").asLong(),
            )
            assertEquals("PAID", ledgerDataAccess.findChargeById(charge.id)?.status?.name)

            val stripeIdempotencyKey = "real-stripe-${UUID.randomUUID()}"
            val metadata = mapOf("idempotencyTest" to stripeIdempotencyKey)
            val firstStripeCharge = stripePaymentService.chargeCard(
                customerId,
                paymentMethod.id,
                BigDecimal("1.00"),
                stripeIdempotencyKey,
                metadata,
            )
            val secondStripeCharge = stripePaymentService.chargeCard(
                customerId,
                paymentMethod.id,
                BigDecimal("1.00"),
                stripeIdempotencyKey,
                metadata,
            )
            assertEquals(firstStripeCharge.paymentIntentId, secondStripeCharge.paymentIntentId)
            val paymentIntents = stripeClient.v1().paymentIntents().list(
                PaymentIntentListParams.builder().setCustomer(customerId).setLimit(100L).build()
            )
            assertEquals(
                1,
                paymentIntents.data.count { it.metadata["rentChargeId"] == charge.id.toString() },
            )
        } finally {
            customerId?.let { stripeClient.v1().customers().delete(it) }
        }
    }

    private fun pay(token: String, chargeId: Long, cardId: Long, idempotencyKey: String): String =
        mockMvc.post("/api/rent-charges/$chargeId/pay") {
            header("Authorization", "Bearer $token")
            header("Idempotency-Key", idempotencyKey)
            contentType = MediaType.APPLICATION_JSON
            content = """{"cardId":$cardId}"""
        }.andExpect {
            status { isAccepted() }
            jsonPath("$.paymentMethod") { value("CREDIT_CARD") }
            jsonPath("$.status") { value("SUCCEEDED") }
        }.andReturn().response.contentAsString

    private fun login(email: String): String {
        val response = mockMvc.post("/api/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"$email","password":"password"}"""
        }.andReturn()
        return objectMapper.readTree(response.response.contentAsString).get("token").asText()
    }

    private fun postSetupWebhook(customerId: String, paymentMethodId: String) {
        val payload = objectMapper.writeValueAsString(
            mapOf(
                "id" to "evt_${UUID.randomUUID()}",
                "object" to "event",
                "api_version" to Stripe.API_VERSION,
                "type" to "setup_intent.succeeded",
                "data" to mapOf(
                    "object" to mapOf(
                        "id" to "seti_${UUID.randomUUID()}",
                        "object" to "setup_intent",
                        "customer" to customerId,
                        "payment_method" to paymentMethodId,
                    )
                ),
            )
        )
        mockMvc.post("/api/webhooks/stripe") {
            contentType = MediaType.APPLICATION_JSON
            content = payload
            header("Stripe-Signature", signature(payload))
        }.andExpect { status { isOk() } }
    }

    private fun signature(payload: String): String {
        val timestamp = System.currentTimeMillis() / 1000
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(WEBHOOK_SECRET.toByteArray(), "HmacSHA256"))
        val digest = mac.doFinal("$timestamp.$payload".toByteArray()).joinToString("") { "%02x".format(it) }
        return "t=$timestamp,v1=$digest"
    }

    companion object {
        private const val WEBHOOK_SECRET = "whsec_real_stripe_integration"
        private val secretKey by lazy {
            requireNotNull(System.getenv("STRIPE_SECRET_KEY")) {
                "STRIPE_SECRET_KEY is required for stripeIntegrationTest"
            }
        }
        private val stripeClient by lazy { StripeClient(secretKey) }

        @DynamicPropertySource
        @JvmStatic
        fun stripeProperties(registry: DynamicPropertyRegistry) {
            registry.add("stripe.secret-key") { secretKey }
            registry.add("stripe.webhook-secret") { WEBHOOK_SECRET }
        }
    }
}
