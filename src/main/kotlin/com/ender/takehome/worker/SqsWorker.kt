package com.ender.takehome.worker

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest

@Component
@EnableScheduling
@ConditionalOnProperty("worker.enabled", havingValue = "true")
class SqsWorker(
    private val sqsClient: SqsClient,
    private val objectMapper: ObjectMapper,
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
            }
        }
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
