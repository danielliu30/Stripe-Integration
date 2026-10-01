package com.ender.takehome.worker

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration

@Configuration
class WorkerConfig {

    @Bean
    fun retryDelayPolicy(
        @Value("\${worker.retry-base-delay-seconds}") baseDelaySeconds: Long,
        @Value("\${worker.retry-max-delay-seconds}") maxDelaySeconds: Long,
        @Value("\${worker.retry-jitter-ratio}") jitterRatio: Double,
    ) = RetryDelayPolicy(
        Duration.ofSeconds(baseDelaySeconds),
        Duration.ofSeconds(maxDelaySeconds),
        jitterRatio,
    )
}
