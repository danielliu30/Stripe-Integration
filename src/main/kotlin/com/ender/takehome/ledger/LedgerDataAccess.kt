package com.ender.takehome.ledger

import com.ender.takehome.generated.tables.Leases.LEASES
import com.ender.takehome.generated.tables.Payments.PAYMENTS
import com.ender.takehome.generated.tables.RentCharges.RENT_CHARGES
import com.ender.takehome.generated.tables.records.PaymentsRecord
import com.ender.takehome.generated.tables.records.RentChargesRecord
import com.ender.takehome.model.Payment
import com.ender.takehome.model.PaymentMethod
import com.ender.takehome.model.PaymentStatus
import com.ender.takehome.model.RentCharge
import com.ender.takehome.model.RentChargeStatus
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneOffset

@Component
class LedgerDataAccess(private val dsl: DSLContext) {

    // --- RentCharge ---

    fun findChargeById(id: Long): RentCharge? =
        dsl.selectFrom(RENT_CHARGES)
            .where(RENT_CHARGES.ID.eq(id))
            .fetchOne()
            ?.toModel()

    /** Locks the rent-charge row until the caller's transaction completes. */
    fun findChargeByIdForUpdate(id: Long): RentCharge? =
        dsl.selectFrom(RENT_CHARGES)
            .where(RENT_CHARGES.ID.eq(id))
            .forUpdate()
            .fetchOne()
            ?.toModel()

    fun findChargesByLeaseIdCursor(leaseId: Long, startAfterId: Long?, limit: Int): List<RentCharge> =
        dsl.selectFrom(RENT_CHARGES)
            .where(RENT_CHARGES.LEASE_ID.eq(leaseId))
            .and(chargeCursorCondition(startAfterId))
            .orderBy(RENT_CHARGES.ID)
            .limit(limit)
            .fetch()
            .map { it.toModel() }

    fun findChargesByLeaseIdAndStatusCursor(leaseId: Long, status: RentChargeStatus, startAfterId: Long?, limit: Int): List<RentCharge> =
        dsl.selectFrom(RENT_CHARGES)
            .where(RENT_CHARGES.LEASE_ID.eq(leaseId))
            .and(RENT_CHARGES.STATUS.eq(status.name))
            .and(chargeCursorCondition(startAfterId))
            .orderBy(RENT_CHARGES.ID)
            .limit(limit)
            .fetch()
            .map { it.toModel() }

    fun findChargeByLeaseIdAndDueDate(leaseId: Long, dueDate: LocalDate): RentCharge? =
        dsl.selectFrom(RENT_CHARGES)
            .where(RENT_CHARGES.LEASE_ID.eq(leaseId))
            .and(RENT_CHARGES.DUE_DATE.eq(dueDate))
            .fetchOne()
            ?.toModel()

    fun saveCharge(charge: RentCharge): RentCharge {
        if (charge.id == 0L) {
            val record = dsl.newRecord(RENT_CHARGES).apply {
                leaseId = charge.leaseId
                amount = charge.amount
                dueDate = charge.dueDate
                status = charge.status.name
            }
            record.store()
            return charge.copy(id = record.id!!)
        }
        dsl.update(RENT_CHARGES)
            .set(RENT_CHARGES.STATUS, charge.status.name)
            .where(RENT_CHARGES.ID.eq(charge.id))
            .execute()
        return charge
    }

    // --- Payment ---

    /** Finds a non-terminal payment that must finish before another attempt can begin. */
    fun findPaymentByIdempotencyKey(idempotencyKey: String): Payment? =
        dsl.selectFrom(PAYMENTS)
            .where(PAYMENTS.IDEMPOTENCY_KEY.eq(idempotencyKey))
            .fetchOne()
            ?.toModel()

    /** Locks a payment while a recovery worker decides whether external work is still needed. */
    fun findPaymentByIdForUpdate(id: Long): Payment? =
        dsl.selectFrom(PAYMENTS)
            .where(PAYMENTS.ID.eq(id))
            .forUpdate()
            .fetchOne()
            ?.toModel()

    /** Locks a payment while a webhook validates and applies its next lifecycle state. */
    fun findPaymentByStripePaymentIntentIdForUpdate(paymentIntentId: String): Payment? =
        dsl.selectFrom(PAYMENTS)
            .where(PAYMENTS.STRIPE_PAYMENT_INTENT_ID.eq(paymentIntentId))
            .forUpdate()
            .fetchOne()
            ?.toModel()

    fun findInFlightPaymentByChargeId(rentChargeId: Long): Payment? =
        dsl.selectFrom(PAYMENTS)
            .where(PAYMENTS.RENT_CHARGE_ID.eq(rentChargeId))
            .and(PAYMENTS.STATUS.notIn(PaymentStatus.SUCCEEDED.name, PaymentStatus.FAILED.name, PaymentStatus.REFUNDED.name))
            .fetchAny()
            ?.toModel()

    /** Calculates settled funds so the client can never choose the amount charged. */
    fun sumSucceededPayments(rentChargeId: Long): BigDecimal =
        dsl.select(DSL.coalesce(DSL.sum(PAYMENTS.AMOUNT), BigDecimal.ZERO))
            .from(PAYMENTS)
            .where(PAYMENTS.RENT_CHARGE_ID.eq(rentChargeId))
            .and(PAYMENTS.STATUS.eq(PaymentStatus.SUCCEEDED.name))
            .fetchOne(0, BigDecimal::class.java) ?: BigDecimal.ZERO

    fun findPaymentsByRentChargeIdCursor(rentChargeId: Long, startAfterId: Long?, limit: Int): List<Payment> =
        dsl.selectFrom(PAYMENTS)
            .where(PAYMENTS.RENT_CHARGE_ID.eq(rentChargeId))
            .and(paymentCursorCondition(startAfterId))
            .orderBy(PAYMENTS.ID)
            .limit(limit)
            .fetch()
            .map { it.toModel() }

    fun findPaymentsByTenantIdCursor(tenantId: Long, startAfterId: Long?, limit: Int): List<Payment> =
        dsl.select(PAYMENTS.fields().toList())
            .from(PAYMENTS)
            .join(RENT_CHARGES).on(RENT_CHARGES.ID.eq(PAYMENTS.RENT_CHARGE_ID))
            .join(LEASES).on(LEASES.ID.eq(RENT_CHARGES.LEASE_ID))
            .where(LEASES.TENANT_ID.eq(tenantId))
            .and(paymentCursorCondition(startAfterId))
            .orderBy(PAYMENTS.ID)
            .limit(limit)
            .fetchInto(PaymentsRecord::class.java)
            .map { it.toModel() }

    fun findAllPaymentsCursor(startAfterId: Long?, limit: Int): List<Payment> =
        dsl.selectFrom(PAYMENTS)
            .where(paymentCursorCondition(startAfterId))
            .orderBy(PAYMENTS.ID)
            .limit(limit)
            .fetch()
            .map { it.toModel() }

    fun savePayment(payment: Payment): Payment {
        if (payment.id == 0L) {
            val record = dsl.newRecord(PAYMENTS).apply {
                rentChargeId = payment.rentChargeId
                amount = payment.amount
                paymentMethod = payment.paymentMethod.name
                status = payment.status.name
                cardId = payment.cardId
                stripePaymentIntentId = payment.stripePaymentIntentId
                idempotencyKey = payment.idempotencyKey
                failureReason = payment.failureReason
                notes = payment.notes
                recordedBy = payment.recordedBy
            }
            record.store()
            return payment.copy(id = record.id!!)
        }
        return payment
    }

    fun updatePaymentStatus(
        id: Long,
        status: PaymentStatus,
        failureReason: String?,
        stripePaymentIntentId: String? = null,
    ): Payment {
        dsl.update(PAYMENTS)
            .set(PAYMENTS.STATUS, status.name)
            .set(PAYMENTS.FAILURE_REASON, failureReason)
            .set(PAYMENTS.STRIPE_PAYMENT_INTENT_ID, stripePaymentIntentId)
            .where(PAYMENTS.ID.eq(id))
            .execute()
        return requireNotNull(
            dsl.selectFrom(PAYMENTS).where(PAYMENTS.ID.eq(id)).fetchOne()?.toModel()
        )
    }

    // --- Cursor helpers ---

    private fun chargeCursorCondition(startAfterId: Long?) =
        if (startAfterId != null) RENT_CHARGES.ID.gt(startAfterId) else DSL.noCondition()

    private fun paymentCursorCondition(startAfterId: Long?) =
        if (startAfterId != null) PAYMENTS.ID.gt(startAfterId) else DSL.noCondition()

    // --- Record mappers ---

    private fun RentChargesRecord.toModel() = RentCharge(
        id = id!!,
        leaseId = leaseId!!,
        amount = amount!!,
        dueDate = dueDate!!,
        status = RentChargeStatus.valueOf(status!!),
        createdAt = createdAt!!.toInstant(ZoneOffset.UTC),
    )

    private fun PaymentsRecord.toModel() = Payment(
        id = id!!,
        rentChargeId = rentChargeId!!,
        amount = amount!!,
        paymentMethod = PaymentMethod.valueOf(paymentMethod!!),
        status = PaymentStatus.valueOf(status!!),
        cardId = cardId,
        stripePaymentIntentId = stripePaymentIntentId,
        idempotencyKey = idempotencyKey,
        failureReason = failureReason,
        notes = notes,
        recordedBy = recordedBy!!,
        createdAt = createdAt!!.toInstant(ZoneOffset.UTC),
    )
}
