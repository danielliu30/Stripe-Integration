package com.ender.takehome.worker

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityRequest
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest
import software.amazon.awssdk.services.sqs.model.Message
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse
import java.time.Duration

class SqsWorkerTest {

    private val sqsClient = mockk<SqsClient>(relaxed = true)
    private val objectMapper = mockk<ObjectMapper>()
    private val handler = TestJob()
    private val worker = SqsWorker(
        sqsClient,
        objectMapper,
        RetryDelayPolicy(Duration.ofSeconds(10), Duration.ofSeconds(60), 0.0),
        listOf(handler),
        QUEUE_URL,
        1,
        30,
    )

    @Test
    fun `failed job uses receive count to change visibility without deleting message`() {
        handler.failure = IllegalStateException("temporary failure")
        val message = message(receiveCount = 2)
        val receiveRequest = slot<ReceiveMessageRequest>()
        val visibilityRequest = slot<ChangeMessageVisibilityRequest>()
        every { sqsClient.receiveMessage(capture(receiveRequest)) } returns response(message)
        every { objectMapper.readValue(message.body(), BackgroundJobRequest::class.java) } returns jobRequest()
        every { sqsClient.changeMessageVisibility(capture(visibilityRequest)) } returns mockk()

        worker.poll()

        assertTrue(
            receiveRequest.captured.messageSystemAttributeNames()
                .contains(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT)
        )
        assertEquals(20, visibilityRequest.captured.visibilityTimeout())
        assertEquals(message.receiptHandle(), visibilityRequest.captured.receiptHandle())
        verify(exactly = 0) { sqsClient.deleteMessage(any<DeleteMessageRequest>()) }
    }

    @Test
    fun `successful job is deleted without changing visibility`() {
        val message = message(receiveCount = 1)
        every { sqsClient.receiveMessage(any<ReceiveMessageRequest>()) } returns response(message)
        every { objectMapper.readValue(message.body(), BackgroundJobRequest::class.java) } returns jobRequest()

        worker.poll()

        assertEquals(1, handler.processed)
        verify {
            sqsClient.deleteMessage(match<DeleteMessageRequest> { it.receiptHandle() == message.receiptHandle() })
        }
        verify(exactly = 0) { sqsClient.changeMessageVisibility(any<ChangeMessageVisibilityRequest>()) }
    }

    @Test
    fun `missing receive count uses first retry delay`() {
        val message = message(receiveCount = null)
        val visibilityRequest = slot<ChangeMessageVisibilityRequest>()
        handler.failure = IllegalStateException("temporary failure")
        every { sqsClient.receiveMessage(any<ReceiveMessageRequest>()) } returns response(message)
        every { objectMapper.readValue(message.body(), BackgroundJobRequest::class.java) } returns jobRequest()
        every { sqsClient.changeMessageVisibility(capture(visibilityRequest)) } returns mockk()

        worker.poll()

        assertEquals(10, visibilityRequest.captured.visibilityTimeout())
    }

    private fun message(receiveCount: Int?) = Message.builder()
        .messageId("message-1")
        .receiptHandle("receipt-1")
        .body("{}")
        .attributes(
            receiveCount?.let {
                mapOf(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT to it.toString())
            } ?: emptyMap()
        )
        .build()

    private fun response(message: Message) = ReceiveMessageResponse.builder().messages(message).build()

    private fun jobRequest() = BackgroundJobRequest(BackgroundJobType.GENERATE_RENT_CHARGES)

    private class TestJob : BackgroundJob<Unit> {
        override val type = BackgroundJobType.GENERATE_RENT_CHARGES
        var processed = 0
        var failure: Exception? = null

        override fun deserialize(params: Map<String, Any>) = Unit

        override fun process(params: Unit) {
            processed++
            failure?.let { throw it }
        }
    }

    private companion object {
        const val QUEUE_URL = "http://localhost/test-queue"
    }
}
