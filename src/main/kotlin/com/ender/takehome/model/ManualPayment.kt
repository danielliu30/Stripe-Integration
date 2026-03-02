package com.ender.takehome.model

import java.math.BigDecimal
import java.time.Instant

enum class PaymentMethod { CASH, CHECK, OTHER }

data class Payment(
    val id: Long = 0,
    val rentChargeId: Long,
    val amount: BigDecimal,
    val paymentMethod: PaymentMethod,
    val notes: String? = null,
    val recordedBy: String,
    val createdAt: Instant = Instant.now(),
)
