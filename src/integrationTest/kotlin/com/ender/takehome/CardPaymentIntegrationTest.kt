package com.ender.takehome

import com.ender.takehome.card.CardDataAccess
import com.ender.takehome.ledger.LedgerDataAccess
import com.ender.takehome.ledger.LedgerModule
import com.ender.takehome.model.Card
import com.ender.takehome.model.Payment
import com.ender.takehome.model.PaymentMethod
import com.ender.takehome.model.PaymentStatus
import com.ender.takehome.model.RentCharge
import com.ender.takehome.stripe.StripeChargeResult
import com.ender.takehome.stripe.StripePaymentService
import com.ender.takehome.tenant.TenantDataAccess
import com.stripe.Stripe
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.jooq.exception.IntegrityConstraintViolationException
import org.springframework.test.web.servlet.post
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@Tag("integration")
@SpringBootTest(properties = [
    "worker.enabled=false",
    "spring.datasource.url=jdbc:h2:mem:card-payment;DB_CLOSE_DELAY=-1;MODE=MYSQL;DATABASE_TO_LOWER=TRUE",
])
@AutoConfigureMockMvc
@Import(CardPaymentIntegrationTest.Config::class)
@Transactional
class CardPaymentIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var tenantDataAccess: TenantDataAccess

    @Autowired
    private lateinit var cardDataAccess: CardDataAccess

    @Autowired
    private lateinit var ledgerDataAccess: LedgerDataAccess

    @Autowired
    private lateinit var ledgerModule: LedgerModule

    @Autowired
    private lateinit var stripeService: FakeStripeService

    @Test
    fun `card payment charges Stripe once and marks rent charge paid`() {
        val tenant = requireNotNull(tenantDataAccess.findById(1L))
        tenantDataAccess.save(tenant.copy(stripeCustomerId = "cus_alice"))
        val card = cardDataAccess.save(
            Card(
                tenantId = 1L,
                stripePaymentMethodId = "pm_alice",
                brand = "visa",
                last4 = "4242",
                expMonth = 12,
                expYear = 2030,
            )
        )
        val charge = ledgerDataAccess.saveCharge(
            RentCharge(leaseId = 1L, amount = BigDecimal("25.00"), dueDate = LocalDate.of(2099, 1, 1))
        )
        val token = login("alice.johnson@email.com")
        val body = """{"cardId":${card.id}}"""

        val first = pay(token, charge.id, body)
        val second = pay(token, charge.id, body)
        val firstPaymentId = objectMapper.readTree(first).get("id").asLong()
        val secondPaymentId = objectMapper.readTree(second).get("id").asLong()

        assertEquals(firstPaymentId, secondPaymentId)
        assertEquals(1, stripeService.chargeCalls)
        assertEquals(listOf("e2e-request-key"), stripeService.idempotencyKeys)
        assertEquals("PAID", ledgerDataAccess.findChargeById(charge.id)?.status?.name)
        assertEquals(1, ledgerDataAccess.findPaymentsByRentChargeIdCursor(charge.id, null, 10).size)
    }

    @Test
    fun `resumes persisted initiated payment without inserting another payment`() {
        val tenant = requireNotNull(tenantDataAccess.findById(1L))
        tenantDataAccess.save(tenant.copy(stripeCustomerId = "cus_alice_recovery"))
        val card = cardDataAccess.save(
            Card(
                tenantId = 1L,
                stripePaymentMethodId = "pm_recovery",
                brand = "visa",
                last4 = "4242",
                expMonth = 12,
                expYear = 2030,
            )
        )
        val charge = ledgerDataAccess.saveCharge(
            RentCharge(leaseId = 1L, amount = BigDecimal("25.00"), dueDate = LocalDate.of(2099, 2, 1))
        )
        val initiated = ledgerDataAccess.savePayment(
            Payment(
                rentChargeId = charge.id,
                amount = charge.amount,
                paymentMethod = PaymentMethod.CREDIT_CARD,
                status = PaymentStatus.INITIATED,
                cardId = card.id,
                idempotencyKey = "persisted-recovery-key",
                recordedBy = tenant.email,
            )
        )

        val result = requireNotNull(ledgerModule.executeInitiatedPayment(initiated.id))

        assertEquals(PaymentStatus.SUCCEEDED, result.payment.status)
        assertEquals("PAID", ledgerDataAccess.findChargeById(charge.id)?.status?.name)
        assertEquals(1, ledgerDataAccess.findPaymentsByRentChargeIdCursor(charge.id, null, 10).size)
        assertEquals("persisted-recovery-key", stripeService.idempotencyKeys.last())
    }

    @Test
    fun `signed Stripe webhooks settle and fully refund a processing payment`() {
        val paymentIntentId = "pi_${UUID.randomUUID()}"
        val payment = ledgerDataAccess.savePayment(
            Payment(
                rentChargeId = 1L,
                amount = BigDecimal("2000.00"),
                paymentMethod = PaymentMethod.CREDIT_CARD,
                status = PaymentStatus.PROCESSING,
                stripePaymentIntentId = paymentIntentId,
                recordedBy = "alice.johnson@email.com",
            )
        )
        val payload = """
            {
              "id": "evt_${UUID.randomUUID()}",
              "object": "event",
              "api_version": "${Stripe.API_VERSION}",
              "type": "payment_intent.succeeded",
              "data": { "object": { "id": "$paymentIntentId", "object": "payment_intent", "status": "succeeded" } }
            }
        """.trimIndent()

        mockMvc.post("/api/webhooks/stripe") {
            contentType = MediaType.APPLICATION_JSON
            content = payload
            header("Stripe-Signature", signature(payload))
        }.andExpect { status { isOk() } }

        val settled = ledgerDataAccess.findPaymentsByRentChargeIdCursor(1L, null, 10)
            .single { it.id == payment.id }
        assertEquals(PaymentStatus.SUCCEEDED, settled.status)
        assertEquals("PAID", ledgerDataAccess.findChargeById(1L)?.status?.name)

        val refundPayload = """
            {
              "id": "evt_${UUID.randomUUID()}",
              "object": "event",
              "api_version": "${Stripe.API_VERSION}",
              "type": "charge.refunded",
              "data": {
                "object": {
                  "id": "ch_${UUID.randomUUID()}",
                  "object": "charge",
                  "payment_intent": "$paymentIntentId",
                  "refunded": true
                }
              }
            }
        """.trimIndent()
        mockMvc.post("/api/webhooks/stripe") {
            contentType = MediaType.APPLICATION_JSON
            content = refundPayload
            header("Stripe-Signature", signature(refundPayload))
        }.andExpect { status { isOk() } }

        val refunded = ledgerDataAccess.findPaymentsByRentChargeIdCursor(1L, null, 10)
            .single { it.id == payment.id }
        assertEquals(PaymentStatus.REFUNDED, refunded.status)
        assertEquals("PENDING", ledgerDataAccess.findChargeById(1L)?.status?.name)
    }

    @Test
    fun `database rejects duplicate payment idempotency key`() {
        val key = "database-${UUID.randomUUID()}"
        val payment = Payment(
            rentChargeId = 1L,
            amount = BigDecimal("1.00"),
            paymentMethod = PaymentMethod.CREDIT_CARD,
            idempotencyKey = key,
            recordedBy = "alice.johnson@email.com",
        )
        ledgerDataAccess.savePayment(payment)

        assertThrows<IntegrityConstraintViolationException> {
            ledgerDataAccess.savePayment(payment)
        }
    }

    private fun pay(token: String, chargeId: Long, body: String): String =
        mockMvc.post("/api/rent-charges/$chargeId/pay") {
            header("Authorization", "Bearer $token")
            header("Idempotency-Key", "e2e-request-key")
            contentType = MediaType.APPLICATION_JSON
            content = body
        }.andExpect {
            status { isAccepted() }
            jsonPath("$.paymentMethod") { value("CREDIT_CARD") }
            jsonPath("$.status") { value("SUCCEEDED") }
            jsonPath("$.recordedBy") { value("alice.johnson@email.com") }
        }.andReturn().response.contentAsString

    private fun login(email: String): String {
        val response = mockMvc.post("/api/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"$email","password":"password"}"""
        }.andReturn()
        return objectMapper.readTree(response.response.contentAsString).get("token").asText()
    }

    private fun signature(payload: String): String {
        val timestamp = System.currentTimeMillis() / 1000
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec("integration-webhook-placeholder".toByteArray(), "HmacSHA256"))
        val digest = mac.doFinal("$timestamp.$payload".toByteArray()).joinToString("") { "%02x".format(it) }
        return "t=$timestamp,v1=$digest"
    }

    @TestConfiguration(proxyBeanMethods = false)
    class Config {
        @Bean
        @Primary
        fun fakeStripeService() = FakeStripeService()
    }

    class FakeStripeService : StripePaymentService {
        var chargeCalls = 0
        val idempotencyKeys = mutableListOf<String>()

        override fun chargeCard(
            customerId: String,
            paymentMethodId: String,
            amount: BigDecimal,
            idempotencyKey: String,
            metadata: Map<String, String>,
        ): StripeChargeResult {
            chargeCalls++
            idempotencyKeys += idempotencyKey
            return StripeChargeResult("pi_$idempotencyKey", PaymentStatus.SUCCEEDED, null)
        }
    }
}
