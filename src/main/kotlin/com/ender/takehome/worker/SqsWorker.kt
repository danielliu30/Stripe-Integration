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

data class JobMessage(
    val jobType: String = "",
    val payload: Map<String, Any> = emptyMap(),
)

@Component
@EnableScheduling
@ConditionalOnProperty("worker.enabled", havingValue = "true")
class SqsWorker(
    private val sqsClient: SqsClient,
    private val objectMapper: ObjectMapper,
    private val jobHandlers: Map<String, JobHandler>,
    @Value("\${aws.sqs.queue-url}") private val queueUrl: String,
    @Value("\${worker.max-messages}") private val maxMessages: Int,
    @Value("\${worker.visibility-timeout-seconds}") private val visibilityTimeout: Int,
) {

    private val log = LoggerFactory.getLogger(SqsWorker::class.java)

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
                val job = objectMapper.readValue(message.body(), JobMessage::class.java)
                log.info("Processing job: type=${job.jobType}")

                val handler = jobHandlers[job.jobType]
                if (handler != null) {
                    handler.handle(job.payload)
                    deleteMessage(message.receiptHandle())
                    log.info("Job completed: type=${job.jobType}")
                } else {
                    log.warn("No handler for job type: ${job.jobType}")
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

interface JobHandler {
    fun handle(payload: Map<String, Any>)
}
