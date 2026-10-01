package com.ender.takehome.worker

import com.ender.takehome.exception.UpstreamException
import com.ender.takehome.ledger.LedgerModule
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class RetryCardPaymentJobTest {

    private val ledgerModule = mockk<LedgerModule>()
    private val job = RetryCardPaymentJob(ledgerModule)

    @Test
    fun `deserializes integer payment id`() {
        assertEquals(RetryCardPaymentParams(42L), job.deserialize(mapOf("paymentId" to 42)))
        assertEquals(RetryCardPaymentParams(43L), job.deserialize(mapOf("paymentId" to 43L)))
    }

    @Test
    fun `rejects missing noninteger and nonpositive payment ids`() {
        assertThrows<IllegalArgumentException> { job.deserialize(emptyMap()) }
        assertThrows<IllegalArgumentException> { job.deserialize(mapOf("paymentId" to "42")) }
        assertThrows<IllegalArgumentException> { job.deserialize(mapOf("paymentId" to 42.5)) }
        assertThrows<IllegalArgumentException> { job.deserialize(mapOf("paymentId" to 0)) }
    }

    @Test
    fun `delegates payment recovery and accepts stale no-op`() {
        every { ledgerModule.executeInitiatedPayment(42L) } returns null

        job.process(RetryCardPaymentParams(42L))

        verify(exactly = 1) { ledgerModule.executeInitiatedPayment(42L) }
    }

    @Test
    fun `propagates uncertain payment failure to worker`() {
        val failure = UpstreamException("Payment processor unavailable", IllegalStateException("connection lost"))
        every { ledgerModule.executeInitiatedPayment(42L) } throws failure

        val thrown = assertThrows<UpstreamException> {
            job.process(RetryCardPaymentParams(42L))
        }

        assertSame(failure, thrown)
    }
}
