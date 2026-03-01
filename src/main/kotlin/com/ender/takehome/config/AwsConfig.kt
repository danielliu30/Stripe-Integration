package com.ender.takehome.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.sqs.SqsClient
import java.net.URI

@Configuration
class AwsConfig(
    @Value("\${aws.region}") private val region: String,
    @Value("\${aws.s3.endpoint}") private val s3Endpoint: String,
    @Value("\${aws.sqs.endpoint}") private val sqsEndpoint: String,
) {
    private val localCredentials = StaticCredentialsProvider.create(
        AwsBasicCredentials.create("test", "test")
    )

    @Bean
    fun s3Client(): S3Client = S3Client.builder()
        .region(Region.of(region))
        .endpointOverride(URI.create(s3Endpoint))
        .credentialsProvider(localCredentials)
        .forcePathStyle(true)
        .build()

    @Bean
    fun sqsClient(): SqsClient = SqsClient.builder()
        .region(Region.of(region))
        .endpointOverride(URI.create(sqsEndpoint))
        .credentialsProvider(localCredentials)
        .build()
}
