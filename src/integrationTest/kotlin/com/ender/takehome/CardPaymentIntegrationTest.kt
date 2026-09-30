package com.ender.takehome

import com.ender.takehome.card.CardDataAccess
import com.ender.takehome.ledger.LedgerDataAccess
import com.ender.takehome.model.Card
import com.ender.takehome.model.PaymentStatus
import com.ender.takehome.model.RentCharge
import com.ender.takehome.stripe.StripeChargeResult
import com.ender.takehome.stripe.StripePaymentService
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
import java.math.BigDecimal
import java.time.LocalDate

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

        val response = pay(token, charge.id, body)
        val paymentId = objectMapper.readTree(response).get("id").asLong()

        assertEquals(1, stripeService.chargeCalls)
        assertEquals(listOf("payment-cus_alice-$paymentId"), stripeService.idempotencyKeys)
        assertEquals("PAID", ledgerDataAccess.findChargeById(charge.id)?.status?.name)
        assertEquals(1, ledgerDataAccess.findPaymentsByRentChargeIdCursor(charge.id, null, 10).size)
    }

    private fun pay(token: String, chargeId: Long, body: String): String =
        mockMvc.post("/api/rent-charges/$chargeId/pay") {
            header("Authorization", "Bearer $token")
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
            return StripeChargeResult("pi_test", PaymentStatus.SUCCEEDED, null)
        }
    }
}
