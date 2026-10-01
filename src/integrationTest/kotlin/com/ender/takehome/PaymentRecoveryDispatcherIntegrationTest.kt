package com.ender.takehome

import com.ender.takehome.ledger.LedgerDataAccess
import com.ender.takehome.ledger.PaymentRecoveryDataAccess
import com.ender.takehome.model.Payment
import com.ender.takehome.model.PaymentMethod
import com.ender.takehome.model.PaymentRecoveryStatus
import com.ender.takehome.model.PaymentStatus
import com.ender.takehome.worker.BackgroundJobRequest
import com.ender.takehome.worker.BackgroundJobType
import com.ender.takehome.worker.JobPublisher
import com.ender.takehome.worker.PaymentRecoveryDispatcher
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.flywaydb.core.Flyway
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest
import java.math.BigDecimal
import java.net.URI
import java.sql.DriverManager
import java.time.Instant

@Tag("integration")
@Testcontainers
class PaymentRecoveryDispatcherIntegrationTest {

    @Test
    fun `due recovery is published to SQS and marked published`() {
        val databaseUrl = "jdbc:h2:mem:recovery-dispatcher;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
        Flyway.configure().dataSource(databaseUrl, "sa", "").load().migrate()
        sqsClient().use { sqs ->
            val queueUrl = sqs.createQueue(
                CreateQueueRequest.builder().queueName("recovery-dispatch-${System.nanoTime()}").build()
            ).queueUrl()
            DriverManager.getConnection(databaseUrl, "sa", "").use { connection ->
                val dsl = DSL.using(connection, SQLDialect.H2)
                val payment = LedgerDataAccess(dsl).savePayment(
                    Payment(
                        rentChargeId = 1L,
                        amount = BigDecimal("1.00"),
                        paymentMethod = PaymentMethod.CREDIT_CARD,
                        status = PaymentStatus.INITIATED,
                        idempotencyKey = "dispatcher-integration",
                        recordedBy = "integration@test.com",
                    )
                )
                val dataAccess = PaymentRecoveryDataAccess(dsl)
                val recovery = dataAccess.create(payment.id, Instant.now().minusSeconds(1))
                val objectMapper = jacksonObjectMapper()
                val dispatcher = PaymentRecoveryDispatcher(
                    dataAccess,
                    JobPublisher(sqs, objectMapper, queueUrl),
                    10,
                    60,
                    30,
                )

                dispatcher.dispatch()

                val message = sqs.receiveMessage(
                    ReceiveMessageRequest.builder()
                        .queueUrl(queueUrl)
                        .maxNumberOfMessages(1)
                        .waitTimeSeconds(1)
                        .build()
                ).messages().single()
                val request = objectMapper.readValue(message.body(), BackgroundJobRequest::class.java)
                assertEquals(BackgroundJobType.RETRY_CARD_PAYMENT, request.type)
                assertEquals(payment.id, (request.params["paymentId"] as Number).toLong())
                assertEquals(PaymentRecoveryStatus.PUBLISHED, dataAccess.findById(recovery.id)?.status)
            }
        }
    }

    private fun sqsClient() = SqsClient.builder()
        .endpointOverride(URI.create("http://${elasticMq.host}:${elasticMq.getMappedPort(9324)}"))
        .region(Region.US_EAST_1)
        .credentialsProvider(
            StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"))
        )
        .build()

    private companion object {
        @Container
        @JvmStatic
        val elasticMq: GenericContainer<*> = GenericContainer("softwaremill/elasticmq-native:1.6.6")
            .withExposedPorts(9324)
    }
}
