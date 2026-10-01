package com.ender.takehome.worker

import com.ender.takehome.ledger.PaymentRecoveryDataAccess
import com.ender.takehome.model.PaymentRecovery
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * Moves durable, due payment recoveries from the database to SQS without holding database locks
 * across network calls. Claims make dispatchers mutually exclusive; stale claims recover crashes,
 * and publication failures return work to `PENDING`. A crash after SQS accepts a message but before
 * acknowledgment persistence may publish a duplicate, which the payment handler is designed to no-op.
 */
@Component
@ConditionalOnProperty("worker.enabled", havingValue = "true")
class PaymentRecoveryDispatcher(
    private val dataAccess: PaymentRecoveryDataAccess,
    private val jobPublisher: JobPublisher,
    @Value("\${payment-recovery.dispatch-batch-size}") private val batchSize: Int,
    @Value("\${payment-recovery.stale-claim-seconds}") private val staleClaimSeconds: Long,
    @Value("\${payment-recovery.publication-retry-delay-seconds}") private val retryDelaySeconds: Long,
) {
    private val log = LoggerFactory.getLogger(PaymentRecoveryDispatcher::class.java)

    init {
        require(batchSize > 0) { "Payment recovery dispatch batch size must be positive" }
        require(staleClaimSeconds > 0) { "Payment recovery stale claim threshold must be positive" }
        require(retryDelaySeconds >= 0) { "Payment recovery publication retry delay must not be negative" }
    }

    @Scheduled(fixedDelayString = "\${payment-recovery.dispatch-interval-ms}")
    fun dispatch() {
        val now = Instant.now()
        val released = dataAccess.releaseStaleClaims(now.minusSeconds(staleClaimSeconds), now, now)
        if (released > 0) log.info("Released $released stale payment recovery claims")
        dataAccess.claimDue(now, batchSize).forEach(::publish)
    }

    private fun publish(recovery: PaymentRecovery) {
        try {
            jobPublisher.publish(
                BackgroundJobRequest(
                    type = BackgroundJobType.RETRY_CARD_PAYMENT,
                    params = mapOf("paymentId" to recovery.paymentId),
                )
            )
        } catch (exception: Exception) {
            val now = Instant.now()
            val released = dataAccess.releaseClaim(
                recovery.id,
                now.plusSeconds(retryDelaySeconds),
                now,
            )
            if (!released) log.info("Payment recovery ${recovery.id} changed before failed publication release")
            log.error("Failed to publish payment recovery ${recovery.id}", exception)
            return
        }
        val published = dataAccess.markPublished(recovery.id, Instant.now())
        if (!published) log.info("Payment recovery ${recovery.id} completed before publication acknowledgment")
    }
}
