package com.ender.takehome.worker

import com.ender.takehome.ledger.PaymentRecoveryDataAccess
import com.ender.takehome.model.PaymentRecovery
import com.ender.takehome.model.PaymentRecoveryStatus
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class PaymentRecoveryDispatcherTest {

    private val dataAccess = mockk<PaymentRecoveryDataAccess>()
    private val jobPublisher = mockk<JobPublisher>()
    private val dispatcher = PaymentRecoveryDispatcher(dataAccess, jobPublisher, 10, 60, 30)

    @Test
    fun `releases stale claims then publishes and acknowledges due recovery`() {
        val recovery = recovery()
        every { dataAccess.releaseStaleClaims(any(), any(), any()) } returns 1
        every { dataAccess.claimDue(any(), 10) } returns listOf(recovery)
        every { jobPublisher.publish(any()) } returns Unit
        every { dataAccess.markPublished(recovery.id, any()) } returns true

        dispatcher.dispatch()

        verify(exactly = 1) {
            jobPublisher.publish(
                BackgroundJobRequest(
                    BackgroundJobType.RETRY_CARD_PAYMENT,
                    mapOf("paymentId" to recovery.paymentId),
                )
            )
        }
        verify(exactly = 1) { dataAccess.markPublished(recovery.id, any()) }
        verify(exactly = 0) { dataAccess.releaseClaim(any(), any(), any()) }
    }

    @Test
    fun `publication failure releases claim after configured delay and continues batch`() {
        val failed = recovery()
        val succeeding = recovery(id = 8L, paymentId = 43L)
        val availableAt = slot<Instant>()
        val updatedAt = slot<Instant>()
        every { dataAccess.releaseStaleClaims(any(), any(), any()) } returns 0
        every { dataAccess.claimDue(any(), 10) } returns listOf(failed, succeeding)
        every {
            jobPublisher.publish(match { (it.params["paymentId"] as Number).toLong() == failed.paymentId })
        } throws IllegalStateException("SQS unavailable")
        every {
            jobPublisher.publish(match { (it.params["paymentId"] as Number).toLong() == succeeding.paymentId })
        } returns Unit
        every {
            dataAccess.releaseClaim(failed.id, capture(availableAt), capture(updatedAt))
        } returns true
        every { dataAccess.markPublished(succeeding.id, any()) } returns true

        dispatcher.dispatch()

        assertEquals(Duration.ofSeconds(30), Duration.between(updatedAt.captured, availableAt.captured))
        verify(exactly = 0) { dataAccess.markPublished(failed.id, any()) }
        verify(exactly = 1) { dataAccess.markPublished(succeeding.id, any()) }
    }

    @Test
    fun `completion race leaves published message for idempotent handler`() {
        val recovery = recovery()
        every { dataAccess.releaseStaleClaims(any(), any(), any()) } returns 0
        every { dataAccess.claimDue(any(), 10) } returns listOf(recovery)
        every { jobPublisher.publish(any()) } returns Unit
        every { dataAccess.markPublished(recovery.id, any()) } returns false

        dispatcher.dispatch()

        verify(exactly = 1) { jobPublisher.publish(any()) }
        verify(exactly = 0) { dataAccess.releaseClaim(any(), any(), any()) }
    }

    @Test
    fun `empty heartbeat only scans and claims`() {
        every { dataAccess.releaseStaleClaims(any(), any(), any()) } returns 0
        every { dataAccess.claimDue(any(), 10) } returns emptyList()

        dispatcher.dispatch()

        verify(exactly = 0) { jobPublisher.publish(any()) }
        verify(exactly = 0) { dataAccess.markPublished(any(), any()) }
    }

    private fun recovery(id: Long = 7L, paymentId: Long = 42L) = PaymentRecovery(
        id = id,
        paymentId = paymentId,
        status = PaymentRecoveryStatus.PUBLISHING,
        availableAt = Instant.EPOCH,
        claimedAt = Instant.EPOCH,
    )
}
