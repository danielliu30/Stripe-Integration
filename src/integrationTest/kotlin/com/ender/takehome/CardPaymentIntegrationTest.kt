package com.ender.takehome

import com.ender.takehome.model.PaymentStatus
import com.ender.takehome.model.Tenant
import com.ender.takehome.stripe.StripeCardDetails
import com.ender.takehome.stripe.StripeChargeResult
import com.ender.takehome.stripe.StripeService
import com.stripe.exception.CardException
import com.stripe.model.Event
import com.stripe.net.Webhook
import org.awaitility.Awaitility
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.math.BigDecimal
import java.time.Duration
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@Tag("integration")
@Import(CardPaymentIntegrationTest.StripeTestConfiguration::class)
class CardPaymentIntegrationTest : IntegrationTestBase() {

    @Autowired
    private lateinit var stripeService: FakeStripeService

    @BeforeEach
    fun resetStripe() {
        stripeService.chargeCalls = 0
    }

    private fun login(email: String): String {
        val result = mockMvc.post("/api/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(mapOf("email" to email, "password" to "password"))
        }.andExpect {
            status { isOk() }
        }.andReturn()
        return objectMapper.readTree(result.response.contentAsString).get("token").asText()
    }

    private fun loginAsPm(): String = login("admin@greenfieldproperties.com")

    private fun createRentCharge(leaseId: Long, dueDate: String): Long {
        val pmToken = loginAsPm()
        mockMvc.post("/api/rent-charges/generate") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(mapOf("dueDate" to dueDate))
            header("Authorization", "Bearer $pmToken")
        }.andExpect {
            status { isAccepted() }
        }

        val formatter = DateTimeFormatter.ISO_LOCAL_DATE
        val targetDate = LocalDate.parse(dueDate, formatter)

        val chargeIdHolder = mutableListOf<Long>()
        Awaitility.await()
            .atMost(Duration.ofSeconds(15))
            .pollInterval(Duration.ofMillis(500))
            .untilAsserted {
                val result = mockMvc.get("/api/rent-charges") {
                    param("leaseId", leaseId.toString())
                    header("Authorization", "Bearer $pmToken")
                }.andExpect {
                    status { isOk() }
                }.andReturn()

                val content = objectMapper.readTree(result.response.contentAsString).get("content")
                val found = (0 until content.size()).map { content[it] }.firstOrNull {
                    val node = it.get("dueDate")
                    val parsed = if (node.isArray) {
                        LocalDate.of(node[0].asInt(), node[1].asInt(), node[2].asInt())
                    } else {
                        LocalDate.parse(node.asText(), formatter)
                    }
                    parsed == targetDate
                }
                assert(found != null) { "Expected rent charge with dueDate $dueDate for lease $leaseId" }
                chargeIdHolder.add(found!!.get("id").asLong())
            }
        return chargeIdHolder.single()
    }

    private fun saveCard(tenantToken: String, setupIntentId: String, paymentMethodId: String, last4: String): Long {
        mockMvc.post("/api/cards/checkout-session") {
            header("Authorization", "Bearer $tenantToken")
        }.andExpect {
            status { isCreated() }
        }

        postWebhook(
            mapOf(
                "id" to "evt_$setupIntentId",
                "object" to "event",
                "api_version" to "2026-06-24.dahlia",
                "type" to "setup_intent.succeeded",
                "data" to mapOf(
                    "object" to mapOf(
                        "id" to setupIntentId,
                        "object" to "setup_intent",
                        "customer" to "cus_test_1",
                        "payment_method" to paymentMethodId,
                    )
                ),
            )
        )

        val cards = mockMvc.get("/api/cards") {
            header("Authorization", "Bearer $tenantToken")
        }.andExpect {
            status { isOk() }
        }.andReturn()
        val cardContent = objectMapper.readTree(cards.response.contentAsString).get("content")
        val savedCard = (0 until cardContent.size()).map { cardContent[it] }.first {
            it.get("last4").asText() == last4
        }
        assert(savedCard.get("last4").asText() == last4)
        return savedCard.get("id").asLong()
    }

    private fun payRentCharge(tenantToken: String, chargeId: Long, cardId: Long, idempotencyKey: String) =
        mockMvc.post("/api/rent-charges/$chargeId/pay") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(mapOf("cardId" to cardId))
            header("Authorization", "Bearer $tenantToken")
            header("Idempotency-Key", idempotencyKey)
        }.andExpect {
            status { isAccepted() }
        }.andReturn()

    private fun completePaymentViaWebhook(paymentIntentId: String) {
        postWebhook(
            mapOf(
                "id" to "evt_$paymentIntentId",
                "object" to "event",
                "api_version" to "2026-06-24.dahlia",
                "type" to "payment_intent.succeeded",
                "data" to mapOf(
                    "object" to mapOf(
                        "id" to paymentIntentId,
                        "object" to "payment_intent",
                        "status" to "succeeded",
                    )
                ),
            )
        )
    }

    private fun postWebhook(event: Map<String, Any>) {
        val payload = objectMapper.writeValueAsString(event)
        mockMvc.post("/api/webhooks/stripe") {
            contentType = MediaType.APPLICATION_JSON
            content = payload
            header("Stripe-Signature", stripeSignature(payload))
        }.andExpect {
            status { isOk() }
        }
    }

    private fun stripeSignature(payload: String): String {
        val timestamp = System.currentTimeMillis() / 1000
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(WEBHOOK_SECRET.toByteArray(), "HmacSHA256"))
        val signature = mac.doFinal("$timestamp.$payload".toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return "t=$timestamp,v1=$signature"
    }

    @TestConfiguration
    class StripeTestConfiguration {
        @Bean
        @Primary
        fun stripeService(): FakeStripeService = FakeStripeService()
    }

    class FakeStripeService : StripeService {
        var chargeCalls = 0

        override fun createCustomer(tenant: Tenant): String = "cus_test_${tenant.id}"

        override fun createSetupCheckoutSession(customerId: String): String =
            "https://checkout.stripe.test/session/$customerId"

        override fun getCardDetails(paymentMethodId: String) =
            StripeCardDetails(
                "visa",
                when {
                    paymentMethodId.contains("ownership") -> "1111"
                    paymentMethodId.contains("idempotency") -> "0001"
                    paymentMethodId.contains("declined") -> "2222"
                    else -> "4242"
                },
                12,
                2030,
            )

        override fun detachPaymentMethod(paymentMethodId: String) = Unit

        override fun chargeCard(
            customerId: String,
            paymentMethodId: String,
            amount: BigDecimal,
            idempotencyKey: String,
            metadata: Map<String, String>,
        ): StripeChargeResult {
            chargeCalls++
            if (paymentMethodId.contains("declined")) {
                throw CardException("Card declined", null, "card_declined", null, "generic_decline", null, 402, null)
            }
            val piId = idempotencyKey.replace(Regex("[^A-Za-z0-9_]"), "_")
            return StripeChargeResult("pi_$piId", PaymentStatus.PROCESSING, null, null)
        }

        override fun constructEvent(payload: String, signature: String): Event =
            Webhook.constructEvent(payload, signature, WEBHOOK_SECRET)
    }

    private companion object {
        const val WEBHOOK_SECRET = "whsec_integration_test"
    }
}
