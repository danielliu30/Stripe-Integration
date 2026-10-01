package com.ender.takehome.ledger

import com.ender.takehome.generated.tables.PaymentRecoveries.PAYMENT_RECOVERIES
import com.ender.takehome.generated.tables.records.PaymentRecoveriesRecord
import com.ender.takehome.model.PaymentRecovery
import com.ender.takehome.model.PaymentRecoveryStatus
import org.jooq.DSLContext
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

@Component
class PaymentRecoveryDataAccess(private val dsl: DSLContext) {

    /** Creates the single durable recovery lifecycle associated with [paymentId]. */
    fun create(paymentId: Long, availableAt: Instant): PaymentRecovery {
        val record = dsl.newRecord(PAYMENT_RECOVERIES).apply {
            this.paymentId = paymentId
            status = PaymentRecoveryStatus.PENDING.name
            this.availableAt = availableAt.toDatabaseTime()
        }
        record.store()
        return requireNotNull(findById(record.id!!))
    }

    fun findById(id: Long): PaymentRecovery? =
        dsl.selectFrom(PAYMENT_RECOVERIES)
            .where(PAYMENT_RECOVERIES.ID.eq(id))
            .fetchOne()
            ?.toModel()

    fun findByPaymentId(paymentId: Long): PaymentRecovery? =
        dsl.selectFrom(PAYMENT_RECOVERIES)
            .where(PAYMENT_RECOVERIES.PAYMENT_ID.eq(paymentId))
            .fetchOne()
            ?.toModel()

    /**
     * Claims due rows with compare-and-set updates rather than holding locks during queue calls.
     * Competing dispatchers may select the same candidates, but only one can move each row from
     * `PENDING` to `PUBLISHING`; this method may therefore return fewer than [limit] under contention.
     * A row completed between the claim and reload is also omitted so stale work is never published.
     */
    fun claimDue(now: Instant, limit: Int): List<PaymentRecovery> {
        require(limit > 0) { "Claim limit must be positive" }
        val databaseNow = now.toDatabaseTime()
        val candidateIds = dsl.select(PAYMENT_RECOVERIES.ID)
            .from(PAYMENT_RECOVERIES)
            .where(PAYMENT_RECOVERIES.STATUS.eq(PaymentRecoveryStatus.PENDING.name))
            .and(PAYMENT_RECOVERIES.AVAILABLE_AT.le(databaseNow))
            .orderBy(PAYMENT_RECOVERIES.AVAILABLE_AT, PAYMENT_RECOVERIES.ID)
            .limit(limit)
            .fetch(PAYMENT_RECOVERIES.ID)
        return candidateIds.mapNotNull { id ->
            val claimed = dsl.update(PAYMENT_RECOVERIES)
                .set(PAYMENT_RECOVERIES.STATUS, PaymentRecoveryStatus.PUBLISHING.name)
                .set(PAYMENT_RECOVERIES.CLAIMED_AT, databaseNow)
                .set(PAYMENT_RECOVERIES.UPDATED_AT, databaseNow)
                .where(PAYMENT_RECOVERIES.ID.eq(id))
                .and(PAYMENT_RECOVERIES.STATUS.eq(PaymentRecoveryStatus.PENDING.name))
                .and(PAYMENT_RECOVERIES.AVAILABLE_AT.le(databaseNow))
                .execute()
            if (claimed == 1) {
                findById(id)?.takeIf { it.status == PaymentRecoveryStatus.PUBLISHING }
            } else null
        }
    }

    /** Moves a currently claimed row to `PUBLISHED` after SQS acknowledges the message. */
    fun markPublished(id: Long, publishedAt: Instant): Boolean {
        val databaseTime = publishedAt.toDatabaseTime()
        return dsl.update(PAYMENT_RECOVERIES)
            .set(PAYMENT_RECOVERIES.STATUS, PaymentRecoveryStatus.PUBLISHED.name)
            .set(PAYMENT_RECOVERIES.PUBLISHED_AT, databaseTime)
            .set(PAYMENT_RECOVERIES.UPDATED_AT, databaseTime)
            .where(PAYMENT_RECOVERIES.ID.eq(id))
            .and(PAYMENT_RECOVERIES.STATUS.eq(PaymentRecoveryStatus.PUBLISHING.name))
            .execute() == 1
    }

    /** Completes recovery from any unfinished state when the payment reaches a known outcome. */
    fun markCompleted(paymentId: Long, completedAt: Instant): Boolean {
        val databaseTime = completedAt.toDatabaseTime()
        return dsl.update(PAYMENT_RECOVERIES)
            .set(PAYMENT_RECOVERIES.STATUS, PaymentRecoveryStatus.COMPLETED.name)
            .set(PAYMENT_RECOVERIES.COMPLETED_AT, databaseTime)
            .set(PAYMENT_RECOVERIES.UPDATED_AT, databaseTime)
            .where(PAYMENT_RECOVERIES.PAYMENT_ID.eq(paymentId))
            .and(PAYMENT_RECOVERIES.STATUS.ne(PaymentRecoveryStatus.COMPLETED.name))
            .execute() == 1
    }

    /** Releases one failed publication claim and schedules its next dispatcher eligibility. */
    fun releaseClaim(id: Long, availableAt: Instant, updatedAt: Instant): Boolean =
        releaseClaims(PAYMENT_RECOVERIES.ID.eq(id), availableAt, updatedAt) == 1

    /** Returns abandoned publisher claims to `PENDING` so another dispatcher can claim them. */
    fun releaseStaleClaims(staleBefore: Instant, availableAt: Instant, updatedAt: Instant): Int =
        releaseClaims(PAYMENT_RECOVERIES.CLAIMED_AT.le(staleBefore.toDatabaseTime()), availableAt, updatedAt)

    private fun releaseClaims(
        condition: org.jooq.Condition,
        availableAt: Instant,
        updatedAt: Instant,
    ): Int = dsl.update(PAYMENT_RECOVERIES)
        .set(PAYMENT_RECOVERIES.STATUS, PaymentRecoveryStatus.PENDING.name)
        .set(PAYMENT_RECOVERIES.AVAILABLE_AT, availableAt.toDatabaseTime())
        .setNull(PAYMENT_RECOVERIES.CLAIMED_AT)
        .set(PAYMENT_RECOVERIES.UPDATED_AT, updatedAt.toDatabaseTime())
        .where(PAYMENT_RECOVERIES.STATUS.eq(PaymentRecoveryStatus.PUBLISHING.name))
        .and(condition)
        .execute()

    private fun PaymentRecoveriesRecord.toModel() = PaymentRecovery(
        id = id!!,
        paymentId = paymentId!!,
        status = PaymentRecoveryStatus.valueOf(status!!),
        availableAt = availableAt!!.toInstant(ZoneOffset.UTC),
        claimedAt = claimedAt?.toInstant(ZoneOffset.UTC),
        publishedAt = publishedAt?.toInstant(ZoneOffset.UTC),
        completedAt = completedAt?.toInstant(ZoneOffset.UTC),
        createdAt = createdAt!!.toInstant(ZoneOffset.UTC),
        updatedAt = updatedAt!!.toInstant(ZoneOffset.UTC),
    )

    private fun Instant.toDatabaseTime() = LocalDateTime.ofInstant(this, ZoneOffset.UTC)
}
