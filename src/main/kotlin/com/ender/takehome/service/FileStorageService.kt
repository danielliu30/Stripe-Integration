package com.ender.takehome.service

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest

@Service
class FileStorageService(
    private val s3Client: S3Client,
    @Value("\${aws.s3.bucket}") private val bucket: String,
) {

    private val log = LoggerFactory.getLogger(FileStorageService::class.java)

    fun upload(key: String, content: ByteArray, contentType: String): String {
        val request = PutObjectRequest.builder()
            .bucket(bucket)
            .key(key)
            .contentType(contentType)
            .build()

        s3Client.putObject(request, RequestBody.fromBytes(content))
        log.info("Uploaded file to s3://$bucket/$key")
        return key
    }

    fun download(key: String): ByteArray {
        val request = GetObjectRequest.builder()
            .bucket(bucket)
            .key(key)
            .build()

        return s3Client.getObject(request).readAllBytes()
    }
}
