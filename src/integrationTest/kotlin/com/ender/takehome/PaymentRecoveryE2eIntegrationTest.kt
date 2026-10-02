package com.ender.takehome

import com.ender.takehome.card.CardDataAccess
import com.ender.takehome.ledger.LedgerDataAccess
import com.ender.takehome.ledger.PaymentRecoveryDataAccess
import com.ender.takehome.model.PaymentRecoveryStatus
import com.ender.takehome.model.PaymentStatus
import com.ender.takehome.model.RentCharge
import com.ender.takehome.stripe.StripeChargeResult
import com.ender.takehome.stripe.StripePaymentService
import com.ender.takehome.stripe.StripeService
import com.ender.takehome.stripe.StripeServiceImpl
import com.ender.takehome.tenant.TenantDataAccess
import com.ender.takehome.worker.BackgroundJobRequest
import com.ender.takehome.worker.BackgroundJobType
import com.fasterxml.jackson.databind.ObjectMapper
import com.stripe.Stripe
import com.stripe.StripeClient
import com.stripe.exception.ApiConnectionException
import com.stripe.param.PaymentIntentListParams
import com.stripe.param.PaymentMethodAttachParams
import com.stripe.param.PaymentMethodCreateParams
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
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
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest
import software.amazon.awssdk.services.sqs.model.SendMessageRequest
import java.math.BigDecimal
import java.net.URI
import java.time.Duration
import java.time.LocalDate
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Connected recovery proof: a real Stripe PaymentIntent is created but its response is dropped,
 * so the API returns `202` with an `INITIATED` payment and a `PENDING` recovery. The scheduled
 * dispatcher then publishes to a real SQS queue, the scheduled worker consumes it, and the retry
 * settles the existing payment via Stripe idempotency — one payment row, one PaymentIntent.
 */
@Tag("stripe")
@Testcontainers
@SpringBootTest(properties = [
    "worker.enabled=true",
    "spring.datasource.url=jdbc:h2:mem:recovery-e2e;DB_CLOSE_DELAY=-1;MODE=MYSQL;DATABASE_TO_LOWER=TRUE",
])
@AutoConfigureMockMvc
@Import(PaymentRecoveryE2eIntegrationTest.DroppedResponseStripeConfig::class)
class PaymentRecoveryE2eIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var tenantDataAccess: TenantDataAccess

    @Autowired
    private lateinit var ledgerDataAccess: LedgerDataAccess

    @Autowired
    private lateinit var recoveryDataAccess: PaymentRecoveryDataAccess

    @Autowired
    private lateinit var cardDataAccess: CardDataAccess

    @Autowired
    private lateinit var stripeService: DroppedResponseStripeService

    @Autowired
    private lateinit var sqsClient: SqsClient

    @Test
    fun `lost Stripe response recovers automatically through dispatcher and queue`() {
        var customerId: String? = null
        try {
            val token = login("alice.johnson@email.com")
            mockMvc.post("/api/cards/checkout-session") {
                header("Authorization", "Bearer $token")
            }.andExpect { status { isOk() } }
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
            val cardId = objectMapper.readTree(
                mockMvc.get("/api/cards") { header("Authorization", "Bearer $token") }
                    .andReturn().response.contentAsString
            ).get("content")[0].get("id").asLong()

            val charge = ledgerDataAccess.saveCharge(
                RentCharge(leaseId = 1L, amount = BigDecimal("1.00"), dueDate = LocalDate.of(2099, 6, 1))
            )

            // Stripe creates the PaymentIntent; the response is dropped before the app sees it.
            stripeService.armNextChargeDrop()
            val idempotencyKey = "e2e-recovery-${UUID.randomUUID()}"
            val response = pay(token, charge.id, cardId, idempotencyKey)
                .andExpect {
                    status { isAccepted() }
                    jsonPath("$.status") { value("INITIATED") }
                }.andReturn().response.contentAsString
            val paymentId = objectMapper.readTree(response).get("id").asLong()

            val initiated = ledgerDataAccess.findPaymentsByRentChargeIdCursor(charge.id, null, 10).single()
            assertEquals(PaymentStatus.INITIATED, initiated.status)
            assertNull(initiated.stripePaymentIntentId)
            assertEquals(
                PaymentRecoveryStatus.PENDING,
                recoveryDataAccess.findByPaymentId(paymentId)?.status,
            )

            // Same-key HTTP replay returns the durable payment and does not charge again.
            val replay = pay(token, charge.id, cardId, idempotencyKey)
                .andExpect {
                    status { isAccepted() }
                    jsonPath("$.status") { value("INITIATED") }
                }.andReturn().response.contentAsString
            assertEquals(paymentId, objectMapper.readTree(replay).get("id").asLong())

            // Dispatcher publishes RETRY_CARD_PAYMENT; the worker settles via the same Stripe key.
            await().atMost(Duration.ofSeconds(60)).untilAsserted {
                val payment = ledgerDataAccess.findPaymentsByRentChargeIdCursor(charge.id, null, 10).single()
                assertEquals(PaymentStatus.SUCCEEDED, payment.status)
                assertNotNull(payment.stripePaymentIntentId)
            }
            val payment = ledgerDataAccess.findPaymentsByRentChargeIdCursor(charge.id, null, 10).single()
            assertEquals("PAID", ledgerDataAccess.findChargeById(charge.id)?.status?.name)
            await().atMost(Duration.ofSeconds(10)).untilAsserted {
                assertEquals(
                    PaymentRecoveryStatus.COMPLETED,
                    recoveryDataAccess.findByPaymentId(paymentId)?.status,
                )
            }

            // Exactly one PaymentIntent exists at Stripe for this charge.
            val paymentIntents = stripeClient.v1().paymentIntents().list(
                PaymentIntentListParams.builder().setCustomer(customerId).setLimit(100L).build()
            )
            val matching = paymentIntents.data.filter { it.metadata["rentChargeId"] == charge.id.toString() }
            assertEquals(1, matching.size)
            assertEquals(payment.stripePaymentIntentId, matching.single().id)

            // A duplicate queue delivery is a no-op and is deleted.
            sqsClient.sendMessage(
                SendMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .messageBody(
                        objectMapper.writeValueAsString(
                            BackgroundJobRequest(
                                type = BackgroundJobType.RETRY_CARD_PAYMENT,
                                params = mapOf("paymentId" to paymentId),
                            )
                        )
                    )
                    .build()
            )
            await().atMost(Duration.ofSeconds(30)).untilAsserted {
                assertTrue(
                    sqsClient.receiveMessage(
                        ReceiveMessageRequest.builder()
                            .queueUrl(queueUrl)
                            .maxNumberOfMessages(10)
                            .visibilityTimeout(0)
                            .waitTimeSeconds(1)
                            .build()
                    ).messages().isEmpty()
                )
            }
            assertEquals(
                PaymentStatus.SUCCEEDED,
                ledgerDataAccess.findPaymentsByRentChargeIdCursor(charge.id, null, 10).single().status,
            )
            assertEquals(
                1,
                stripeClient.v1().paymentIntents().list(
                    PaymentIntentListParams.builder().setCustomer(customerId).setLimit(100L).build()
                ).data.count { it.metadata["rentChargeId"] == charge.id.toString() },
            )

            // A late duplicate webhook for the same PaymentIntent is a no-op.
            postPaymentWebhook(payment.stripePaymentIntentId!!)
            assertEquals(
                PaymentStatus.SUCCEEDED,
                ledgerDataAccess.findPaymentsByRentChargeIdCursor(charge.id, null, 10).single().status,
            )
            assertEquals("PAID", ledgerDataAccess.findChargeById(charge.id)?.status?.name)
        } finally {
            customerId?.let { stripeClient.v1().customers().delete(it) }
        }
    }

    private fun pay(token: String, chargeId: Long, cardId: Long, idempotencyKey: String) =
        mockMvc.post("/api/rent-charges/$chargeId/pay") {
            header("Authorization", "Bearer $token")
            header("Idempotency-Key", idempotencyKey)
            contentType = MediaType.APPLICATION_JSON
            content = """{"cardId":$cardId}"""
        }

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

    private fun postPaymentWebhook(paymentIntentId: String) {
        val payload = objectMapper.writeValueAsString(
            mapOf(
                "id" to "evt_${UUID.randomUUID()}",
                "object" to "event",
                "api_version" to Stripe.API_VERSION,
                "type" to "payment_intent.succeeded",
                "data" to mapOf(
                    "object" to mapOf(
                        "id" to paymentIntentId,
                        "object" to "payment_intent",
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

    /**
     * Delegates every Stripe operation to the real client. When armed, the first `chargeCard`
     * performs the real create — so Stripe owns the PaymentIntent — then throws as if the
     * response never arrived, exercising the uncertain-outcome path against a live charge.
     */
    class DroppedResponseStripeService(
        private val delegate: StripeServiceImpl,
    ) : StripeService by delegate, StripePaymentService by delegate {

        @Volatile
        private var armed = false

        fun armNextChargeDrop() {
            armed = true
        }

        override fun chargeCard(
            customerId: String,
            paymentMethodId: String,
            amount: BigDecimal,
            idempotencyKey: String,
            metadata: Map<String, String>,
        ): StripeChargeResult {
            val result = delegate.chargeCard(customerId, paymentMethodId, amount, idempotencyKey, metadata)
            if (armed) {
                armed = false
                throw ApiConnectionException("Simulated lost Stripe response")
            }
            return result
        }
    }

    @TestConfiguration
    class DroppedResponseStripeConfig {
        @Bean
        @Primary
        fun droppedResponseStripeService(delegate: StripeServiceImpl) = DroppedResponseStripeService(delegate)
    }

    companion object {
        private const val WEBHOOK_SECRET = "whsec_recovery_e2e"

        private val secretKey by lazy {
            requireNotNull(System.getenv("STRIPE_SECRET_KEY")) {
                "STRIPE_SECRET_KEY is required for stripeIntegrationTest"
            }
        }
        private val stripeClient by lazy { StripeClient(secretKey) }

        @Container
        private val elasticMq: GenericContainer<*> = GenericContainer("softwaremill/elasticmq-native:1.6.6")
            .withExposedPorts(9324)

        private val queueUrl: String by lazy {
            sqsEndpointClient().use { sqs ->
                sqs.createQueue(
                    CreateQueueRequest.builder().queueName("recovery-e2e-${System.nanoTime()}").build()
                ).queueUrl()
            }
        }

        private fun sqsEndpointClient(): SqsClient = SqsClient.builder()
            .endpointOverride(URI.create("http://${elasticMq.host}:${elasticMq.getMappedPort(9324)}"))
            .region(Region.US_EAST_1)
            .credentialsProvider(
                StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"))
            )
            .build()

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("stripe.secret-key") { secretKey }
            registry.add("stripe.webhook-secret") { WEBHOOK_SECRET }
            registry.add("aws.sqs.endpoint") {
                "http://${elasticMq.host}:${elasticMq.getMappedPort(9324)}"
            }
            registry.add("aws.sqs.queue-url") { queueUrl }
        }
    }
}
