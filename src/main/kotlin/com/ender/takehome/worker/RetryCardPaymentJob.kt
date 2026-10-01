package com.ender.takehome.worker

import com.ender.takehome.ledger.LedgerModule
import org.springframework.stereotype.Component

data class RetryCardPaymentParams(val paymentId: Long)

/**
 * Executes durable recovery for one persisted payment.
 *
 * [LedgerModule.executeInitiatedPayment] owns state gating, locking, Stripe idempotency, and
 * settlement. This handler deliberately does not catch failures: uncertain errors must reach
 * [SqsWorker] so the message receives backoff and eventually follows the queue's redrive policy.
 * Missing or already settled payments are successful no-ops and allow stale messages to be deleted.
 */
@Component
class RetryCardPaymentJob(
    private val ledgerModule: LedgerModule,
) : BackgroundJob<RetryCardPaymentParams> {

    override val type = BackgroundJobType.RETRY_CARD_PAYMENT

    override fun deserialize(params: Map<String, Any>): RetryCardPaymentParams {
        val paymentId = when (val value = params["paymentId"]) {
            is Byte, is Short, is Int, is Long -> (value as Number).toLong()
            else -> throw IllegalArgumentException("paymentId must be an integer")
        }
        require(paymentId > 0) { "paymentId must be positive" }
        return RetryCardPaymentParams(paymentId)
    }

    override fun process(params: RetryCardPaymentParams) {
        ledgerModule.executeInitiatedPayment(params.paymentId)
    }
}
