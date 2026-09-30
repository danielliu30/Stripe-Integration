package com.ender.takehome.model

import java.math.BigDecimal
import java.time.Instant

enum class PaymentMethod { CASH, CHECK, OTHER, CREDIT_CARD }

enum class PaymentStatus { INITIATED, REQUIRES_ACTION, PROCESSING, SUCCEEDED, FAILED, REFUNDED }

data class Payment(
    val id: Long = 0,
    val rentChargeId: Long,
    val amount: BigDecimal,
    val paymentMethod: PaymentMethod,
    val status: PaymentStatus = PaymentStatus.SUCCEEDED,
    val cardId: Long? = null,
    val stripePaymentIntentId: String? = null,
    val idempotencyKey: String? = null,
    val failureReason: String? = null,
    val notes: String? = null,
    val recordedBy: String,
    val createdAt: Instant = Instant.now(),
)
