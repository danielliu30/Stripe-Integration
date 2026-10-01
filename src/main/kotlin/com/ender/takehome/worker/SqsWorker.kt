package com.ender.takehome.worker

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityRequest
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest
import software.amazon.awssdk.services.sqs.model.Message
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest

/**
 * Polls and dispatches SQS jobs with at-least-once delivery semantics.
 *
 * A job is deleted only after its handler completes. Failures remain in SQS and receive a
 * policy-derived visibility delay before redelivery, so handlers must tolerate duplicate execution.
 * Queue exhaustion and dead-letter routing are broker concerns introduced separately from this
 * worker-level retry timing behavior.
 */
@Component
@EnableScheduling
@ConditionalOnProperty("worker.enabled", havingValue = "true")
class SqsWorker(
    private val sqsClient: SqsClient,
    private val objectMapper: ObjectMapper,
    private val retryDelayPolicy: RetryDelayPolicy,
    backgroundJobs: List<BackgroundJob<*>>,
    @Value("\${aws.sqs.queue-url}") private val queueUrl: String,
    @Value("\${worker.max-messages}") private val maxMessages: Int,
    @Value("\${worker.visibility-timeout-seconds}") private val visibilityTimeout: Int,
) {

    private val log = LoggerFactory.getLogger(SqsWorker::class.java)

    private val handlers: Map<BackgroundJobType, BackgroundJob<*>> =
        backgroundJobs.associateBy { it.type }

    @Scheduled(fixedDelayString = "\${worker.poll-interval-ms}")
    fun poll() {
        val request = ReceiveMessageRequest.builder()
            .queueUrl(queueUrl)
            .maxNumberOfMessages(maxMessages)
            .visibilityTimeout(visibilityTimeout)
            .messageSystemAttributeNames(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT)
            .waitTimeSeconds(5)
            .build()

        val messages = sqsClient.receiveMessage(request).messages()
        for (message in messages) {
            try {
                val job = objectMapper.readValue(message.body(), BackgroundJobRequest::class.java)
                log.info("Processing job: type=${job.type}")

                val handler = handlers[job.type]
                if (handler != null) {
                    handler.handleRaw(job.params)
                    deleteMessage(message.receiptHandle())
                    log.info("Job completed: type=${job.type}")
                } else {
                    log.warn("No handler registered for job type: ${job.type}")
                }
            } catch (e: Exception) {
                log.error("Failed to process message: ${message.messageId()}", e)
                runCatching { scheduleRetry(message) }
                    .onFailure { log.error("Failed to schedule message retry: ${message.messageId()}", it) }
            }
        }
    }

    /**
     * Keeps a failed message in SQS and moves its next delivery using the broker's receive count.
     * A visibility update failure is logged by [poll]; the original visibility timeout still makes
     * the message eligible for another delivery, so worker polling can continue safely.
     */
    private fun scheduleRetry(message: Message) {
        val receiveCount = message.attributes()[MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT]
            ?.toIntOrNull()
            ?: 1
        val delay = retryDelayPolicy.delay(receiveCount)
        sqsClient.changeMessageVisibility(
            ChangeMessageVisibilityRequest.builder()
                .queueUrl(queueUrl)
                .receiptHandle(message.receiptHandle())
                .visibilityTimeout(delay.seconds.toInt())
                .build()
        )
        log.info("Scheduled message retry: id=${message.messageId()}, receiveCount=$receiveCount, delay=$delay")
    }

    private fun deleteMessage(receiptHandle: String) {
        sqsClient.deleteMessage(
            DeleteMessageRequest.builder()
                .queueUrl(queueUrl)
                .receiptHandle(receiptHandle)
                .build()
        )
    }
}
