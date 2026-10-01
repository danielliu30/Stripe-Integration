package com.ender.takehome

import com.ender.takehome.worker.BackgroundJob
import com.ender.takehome.worker.BackgroundJobRequest
import com.ender.takehome.worker.BackgroundJobType
import com.ender.takehome.worker.RetryDelayPolicy
import com.ender.takehome.worker.SqsWorker
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
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
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest
import software.amazon.awssdk.services.sqs.model.SendMessageRequest
import java.net.URI
import java.time.Duration

@Tag("integration")
@Testcontainers
class SqsWorkerRetryIntegrationTest {

    private val objectMapper = jacksonObjectMapper()
    private lateinit var sqsClient: SqsClient
    private lateinit var queueUrl: String

    @BeforeEach
    fun setUp() {
        sqsClient = SqsClient.builder()
            .endpointOverride(URI.create("http://${elasticMq.host}:${elasticMq.getMappedPort(9324)}"))
            .region(Region.US_EAST_1)
            .credentialsProvider(
                StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"))
            )
            .build()
        queueUrl = sqsClient.createQueue(
            CreateQueueRequest.builder().queueName("retry-${System.nanoTime()}").build()
        ).queueUrl()
    }

    @AfterEach
    fun tearDown() {
        sqsClient.close()
    }

    @Test
    fun `failed message stays hidden until policy delay then returns with next receive count`() {
        val worker = SqsWorker(
            sqsClient,
            objectMapper,
            RetryDelayPolicy(Duration.ofSeconds(1), Duration.ofSeconds(2), 0.0),
            listOf(FailingJob()),
            queueUrl,
            1,
            30,
        )
        sqsClient.sendMessage(
            SendMessageRequest.builder()
                .queueUrl(queueUrl)
                .messageBody(objectMapper.writeValueAsString(BackgroundJobRequest()))
                .build()
        )

        worker.poll()

        assertTrue(receive().isEmpty())
        Thread.sleep(1_200)
        val retried = receive()
        assertEquals(1, retried.size)
        assertEquals(
            "2",
            retried.single().attributes()[MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT],
        )
    }

    private fun receive() = sqsClient.receiveMessage(
        ReceiveMessageRequest.builder()
            .queueUrl(queueUrl)
            .maxNumberOfMessages(1)
            .waitTimeSeconds(0)
            .messageSystemAttributeNames(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT)
            .build()
    ).messages()

    private class FailingJob : BackgroundJob<Unit> {
        override val type = BackgroundJobType.GENERATE_RENT_CHARGES
        override fun deserialize(params: Map<String, Any>) = Unit
        override fun process(params: Unit) = throw IllegalStateException("temporary failure")
    }

    private companion object {
        @Container
        @JvmStatic
        val elasticMq: GenericContainer<*> = GenericContainer("softwaremill/elasticmq-native:1.6.6")
            .withExposedPorts(9324)
    }
}
