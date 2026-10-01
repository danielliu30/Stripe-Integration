package com.ender.takehome.model

import java.time.Instant

data class Card(
    val id: Long = 0,
    val tenantId: Long,
    val stripePaymentMethodId: String,
    val brand: String,
    val last4: String,
    val expMonth: Int,
    val expYear: Int,
    val createdAt: Instant = Instant.now(),
    val deletedAt: Instant? = null,
)
