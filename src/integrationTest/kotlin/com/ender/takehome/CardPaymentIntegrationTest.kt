package com.ender.takehome

import com.ender.takehome.ledger.LedgerDataAccess
import com.ender.takehome.tenant.TenantDataAccess
import com.fasterxml.jackson.core.type.TypeReference
import org.awaitility.Awaitility
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.time.Duration
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@Tag("integration")
class CardPaymentIntegrationTest : IntegrationTestBase() {

    @Value("\${stripe.secret-key}")
    private lateinit var stripeSecretKey: String

    @Value("\${stripe.webhook-secret}")
    private lateinit var webhookSecret: String

    @Autowired
    private lateinit var tenantDataAccess: TenantDataAccess

    @Autowired
    private lateinit var ledgerDataAccess: LedgerDataAccess

    private val stripeClient by lazy { com.stripe.StripeClient(stripeSecretKey) }

    @Test
    fun `tenant saves card through Checkout and pays rent through full lifecycle`() {
        val tenantEmail = "alice.johnson@email.com"
        val tenantToken = login(tenantEmail)
        val chargeId = createRentCharge(leaseId = 1, dueDate = "2099-01-01")
        val cardId = saveCard(tenantEmail, tenantToken, "tok_visa")

        val payment = payRentCharge(tenantToken, chargeId, cardId, idempotencyKey = uniqueKey("happy"))
        val paymentId = objectMapper.readTree(payment.response.contentAsString).get("id").asLong()

        mockMvc.get("/api/payments/$paymentId") {
            header("Authorization", "Bearer $tenantToken")
        }.andExpect {
            status { isOk() }
            jsonPath("$.status") { value("SUCCEEDED") }
            jsonPath("$.card.last4") { value("4242") }
        }
        mockMvc.get("/api/rent-charges/$chargeId") {
            header("Authorization", "Bearer $tenantToken")
        }.andExpect {
            status { isOk() }
            jsonPath("$.status") { value("PAID") }
        }
    }

    @Test
    fun `duplicate setup intent succeeded webhooks create only one saved card`() {
        val tenantEmail = "alice.johnson@email.com"
        val tenantToken = login(tenantEmail)

        saveCard(tenantEmail, tenantToken, "tok_visa")
        val pm = createStripePaymentMethod("tok_mastercard")
        val event = buildSetupIntentWebhook(tenantEmail, pm.id)

        postWebhook(event)

        val cardsAfterFirst = mockMvc.get("/api/cards") {
            header("Authorization", "Bearer $tenantToken")
        }.andReturn()
        val firstCardContent = objectMapper.readTree(cardsAfterFirst.response.contentAsString).get("content")
        val cardsAfterFirstCount = firstCardContent.size()
        assert(cardsAfterFirstCount > 1) { "Expected at least two saved cards" }

        postWebhook(event + mapOf("id" to "evt_${UUID.randomUUID()}"))

        val cardsAfterSecond = mockMvc.get("/api/cards") {
            header("Authorization", "Bearer $tenantToken")
        }.andExpect {
            status { isOk() }
        }.andReturn()
        val secondCardContent = objectMapper.readTree(cardsAfterSecond.response.contentAsString).get("content")
        assertEquals(cardsAfterFirstCount, secondCardContent.size())
    }

    @Test
    fun `replaying payment with same idempotency key returns original payment without re-charging`() {
        val tenantEmail = "alice.johnson@email.com"
        val tenantToken = login(tenantEmail)
        val chargeId = createRentCharge(leaseId = 1, dueDate = "2099-02-01")
        val cardId = saveCard(tenantEmail, tenantToken, "tok_visa")

        val idempotencyKey = uniqueKey("idempotency")
        val payment = payRentCharge(tenantToken, chargeId, cardId, idempotencyKey)
        val paymentId = objectMapper.readTree(payment.response.contentAsString).get("id").asLong()

        val replay = payRentCharge(tenantToken, chargeId, cardId, idempotencyKey)
        val replayPaymentId = objectMapper.readTree(replay.response.contentAsString).get("id").asLong()

        assertEquals(paymentId, replayPaymentId)
    }

    @Test
    fun `tenant cannot pay another tenant's rent charge with saved card`() {
        val tenantEmail = "alice.johnson@email.com"
        val tenantToken = login(tenantEmail)
        val otherTenantChargeId = createRentCharge(leaseId = 2, dueDate = "2099-03-01")

        saveCard(tenantEmail, tenantToken, "tok_amex")

        val cards = mockMvc.get("/api/cards") {
            header("Authorization", "Bearer $tenantToken")
        }.andReturn()
        val cardId = objectMapper.readTree(cards.response.contentAsString).get("content")[0].get("id").asLong()

        mockMvc.post("/api/rent-charges/$otherTenantChargeId/pay") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(mapOf("cardId" to cardId))
            header("Authorization", "Bearer $tenantToken")
        }.andExpect {
            status { isNotFound() }
        }
    }

    @Test
    fun `refund webhook reopens rent charge`() {
        val tenantEmail = "alice.johnson@email.com"
        val tenantToken = login(tenantEmail)
        val chargeId = createRentCharge(leaseId = 1, dueDate = "2099-08-01")
        val cardId = saveCard(tenantEmail, tenantToken, "tok_visa")

        val payment = payRentCharge(tenantToken, chargeId, cardId, idempotencyKey = uniqueKey("refund"))
        val paymentId = objectMapper.readTree(payment.response.contentAsString).get("id").asLong()

        val piId = ledgerDataAccess.findPaymentById(paymentId)?.stripePaymentIntentId
            ?: error("Payment not found")

        val refund = stripeClient.v1().refunds().create(
            com.stripe.param.RefundCreateParams.builder().setPaymentIntent(piId).build()
        )

        val event = fetchStripeEvent("charge.refunded", refund.charge)
        postWebhook(event)

        mockMvc.get("/api/payments/$paymentId") {
            header("Authorization", "Bearer $tenantToken")
        }.andExpect {
            status { isOk() }
            jsonPath("$.status") { value("REFUNDED") }
        }
        mockMvc.get("/api/rent-charges/$chargeId") {
            header("Authorization", "Bearer $tenantToken")
        }.andExpect {
            status { isOk() }
            jsonPath("$.status") { value("PENDING") }
        }
    }

    @Test
    fun `cannot pay rent charge that is already paid`() {
        val tenantEmail = "alice.johnson@email.com"
        val tenantToken = login(tenantEmail)
        val chargeId = createRentCharge(leaseId = 1, dueDate = "2099-05-01")
        val cardId = saveCard(tenantEmail, tenantToken, "tok_visa")

        payRentCharge(tenantToken, chargeId, cardId, idempotencyKey = uniqueKey("already-paid"))

        mockMvc.post("/api/rent-charges/$chargeId/pay") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(mapOf("cardId" to cardId))
            header("Authorization", "Bearer $tenantToken")
            header("Idempotency-Key", uniqueKey("already-paid-retry"))
        }.andExpect {
            status { isConflict() }
        }
    }

    @Test
    fun `cannot pay with non-existent card`() {
        val tenantEmail = "alice.johnson@email.com"
        val tenantToken = login(tenantEmail)
        val chargeId = createRentCharge(leaseId = 1, dueDate = "2099-06-01")

        mockMvc.post("/api/rent-charges/$chargeId/pay") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(mapOf("cardId" to 999_999L))
            header("Authorization", "Bearer $tenantToken")
        }.andExpect {
            status { isNotFound() }
        }
    }

    @Test
    fun `invalid webhook signature returns bad request`() {
        val payload = objectMapper.writeValueAsString(
            mapOf(
                "id" to "evt_invalid",
                "object" to "event",
                "type" to "payment_intent.succeeded",
                "data" to mapOf("object" to mapOf("id" to "pi_invalid")),
            )
        )
        mockMvc.post("/api/webhooks/stripe") {
            contentType = MediaType.APPLICATION_JSON
            content = payload
            header("Stripe-Signature", "t=1,v1=invalidsignature")
        }.andExpect {
            status { isBadRequest() }
        }
    }

    @Test
    fun `cannot pay with deleted saved card`() {
        val tenantEmail = "bob.smith@email.com"
        val tenantToken = login(tenantEmail)
        val chargeId = createRentCharge(leaseId = 2, dueDate = "2099-07-01")
        val cardId = saveCard(tenantEmail, tenantToken, "tok_visa")

        mockMvc.delete("/api/cards/$cardId") {
            header("Authorization", "Bearer $tenantToken")
        }.andExpect {
            status { isNoContent() }
        }

        mockMvc.post("/api/rent-charges/$chargeId/pay") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(mapOf("cardId" to cardId))
            header("Authorization", "Bearer $tenantToken")
        }.andExpect {
            status { isNotFound() }
        }
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

    private fun saveCard(tenantEmail: String, tenantToken: String, tokenId: String): Long {
        mockMvc.post("/api/cards/checkout-session") {
            header("Authorization", "Bearer $tenantToken")
        }.andExpect {
            status { isCreated() }
        }

        val pm = createStripePaymentMethod(tokenId)
        val event = buildSetupIntentWebhook(tenantEmail, pm.id)
        postWebhook(event)

        return findLocalCard(tenantToken, pm.card?.last4 ?: error("Missing card last4"))
    }

    private fun buildSetupIntentWebhook(tenantEmail: String, paymentMethodId: String): Map<String, Any> {
        val customerId = tenantStripeCustomerId(tenantEmail)
        attachPaymentMethod(paymentMethodId, customerId)

        return mapOf(
            "id" to "evt_${UUID.randomUUID()}",
            "object" to "event",
            "api_version" to com.stripe.Stripe.API_VERSION,
            "type" to "setup_intent.succeeded",
            "data" to mapOf(
                "object" to mapOf(
                    "id" to "seti_${UUID.randomUUID()}",
                    "object" to "setup_intent",
                    "customer" to customerId,
                    "payment_method" to paymentMethodId,
                    "client_secret" to "seti_secret_placeholder",
                )
            ),
        )
    }

    private fun createStripePaymentMethod(tokenId: String): com.stripe.model.PaymentMethod {
        val params = com.stripe.param.PaymentMethodCreateParams.builder()
            .setType(com.stripe.param.PaymentMethodCreateParams.Type.CARD)
            .setCard(
                com.stripe.param.PaymentMethodCreateParams.Token.builder()
                    .setToken(tokenId)
                    .build()
            )
            .build()
        return stripeClient.v1().paymentMethods().create(params)
    }

    private fun attachPaymentMethod(paymentMethodId: String, customerId: String) {
        stripeClient.v1().paymentMethods().attach(
            paymentMethodId,
            com.stripe.param.PaymentMethodAttachParams.builder().setCustomer(customerId).build()
        )
    }

    private fun tenantStripeCustomerId(tenantEmail: String): String {
        val tenant = tenantDataAccess.findByEmail(tenantEmail) ?: error("Tenant not found")
        return tenant.stripeCustomerId ?: error("Stripe customer not created")
    }

    private fun findLocalCard(tenantToken: String, last4: String): Long {
        val cards = mockMvc.get("/api/cards") {
            header("Authorization", "Bearer $tenantToken")
        }.andExpect {
            status { isOk() }
        }.andReturn()
        val cardContent = objectMapper.readTree(cards.response.contentAsString).get("content")
        val savedCard = (0 until cardContent.size()).map { cardContent[it] }.first {
            it.get("last4").asText() == last4
        }
        return savedCard.get("id").asLong()
    }

    private fun fetchStripeEvent(type: String, objectId: String): Map<String, Any> {
        val charge = stripeClient.v1().charges().retrieve(objectId)
        return mapOf(
            "id" to "evt_${UUID.randomUUID()}",
            "object" to "event",
            "api_version" to com.stripe.Stripe.API_VERSION,
            "type" to type,
            "data" to mapOf(
                "object" to mapOf(
                    "id" to charge.id,
                    "object" to "charge",
                    "payment_intent" to charge.paymentIntent,
                    "refunded" to charge.refunded,
                )
            ),
        )
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
        mac.init(SecretKeySpec(webhookSecret.toByteArray(), "HmacSHA256"))
        val signature = mac.doFinal("$timestamp.$payload".toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return "t=$timestamp,v1=$signature"
    }

    private fun uniqueKey(prefix: String): String = "${prefix}_${UUID.randomUUID()}"
}
