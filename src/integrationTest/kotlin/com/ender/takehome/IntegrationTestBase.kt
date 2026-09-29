package com.ender.takehome

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest
import java.net.URI

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
abstract class IntegrationTestBase {

    companion object {
        private const val QUEUE_NAME = "test-jobs"

        @Container
        @JvmStatic
        val elasticMq: GenericContainer<*> = GenericContainer("softwaremill/elasticmq-native:1.6.6")
            .withExposedPorts(9324)

        @DynamicPropertySource
        @JvmStatic
        fun configureProperties(registry: DynamicPropertyRegistry) {
            val endpoint = "http://${elasticMq.host}:${elasticMq.getMappedPort(9324)}"

            // Create the queue before Spring boots (SqsWorker starts polling immediately)
            val client = SqsClient.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.US_EAST_1)
                .credentialsProvider(
                    StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"))
                )
                .build()

            val queueUrl = client.createQueue(
                CreateQueueRequest.builder().queueName(QUEUE_NAME).build()
            ).queueUrl()
            client.close()

            registry.add("aws.sqs.endpoint") { endpoint }
            registry.add("aws.sqs.queue-url") { queueUrl }

            registry.add("stripe.secret-key") { System.getenv("STRIPE_SECRET_KEY") ?: "" }
            registry.add("stripe.webhook-secret") { System.getenv("STRIPE_WEBHOOK_SECRET") ?: "whsec_test_secret_for_integration_tests" }
            registry.add("stripe.return-url") { System.getenv("STRIPE_RETURN_URL") ?: "http://localhost/api/checkout/return?status=success" }
        }
    }

    @Autowired
    protected lateinit var mockMvc: MockMvc

    @Autowired
    protected lateinit var objectMapper: ObjectMapper
}
