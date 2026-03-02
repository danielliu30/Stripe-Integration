package com.ender.takehome.worker

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model.SendMessageRequest

@Component
class JobPublisher(
    private val sqsClient: SqsClient,
    private val objectMapper: ObjectMapper,
    @Value("\${aws.sqs.queue-url}") private val queueUrl: String,
) {

    private val log = LoggerFactory.getLogger(JobPublisher::class.java)

    fun publish(request: BackgroundJobRequest) {
        val body = objectMapper.writeValueAsString(request)
        sqsClient.sendMessage(
            SendMessageRequest.builder()
                .queueUrl(queueUrl)
                .messageBody(body)
                .build()
        )
        log.info("Published job: type=${request.type}")
    }
}
