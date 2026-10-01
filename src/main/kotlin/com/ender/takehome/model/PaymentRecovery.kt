package com.ender.takehome.model

import java.time.Instant

/** Durable publication lifecycle for one payment's recovery responsibility. */
enum class PaymentRecoveryStatus {
    PENDING,
    PUBLISHING,
    PUBLISHED,
    COMPLETED,
}

/**
 * Durable recovery state retained independently from transient dispatcher and SQS processes.
 * [availableAt] controls initial/released eligibility; claim, publication, and completion timestamps
 * preserve operational history while [status] determines which compare-and-set transition is legal.
 */
data class PaymentRecovery(
    val id: Long = 0,
    val paymentId: Long,
    val status: PaymentRecoveryStatus = PaymentRecoveryStatus.PENDING,
    val availableAt: Instant,
    val claimedAt: Instant? = null,
    val publishedAt: Instant? = null,
    val completedAt: Instant? = null,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
)
