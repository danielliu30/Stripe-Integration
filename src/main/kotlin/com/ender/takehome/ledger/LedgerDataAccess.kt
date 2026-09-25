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

    /** Locks the charge row — serializes concurrent pay attempts on the same charge. */
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
            .set(DSL.field(RENT_CHARGES.STATUS.unqualifiedName, RENT_CHARGES.STATUS.dataType), charge.status.name)
            .where(RENT_CHARGES.ID.eq(charge.id))
            .execute()
        return charge
    }

    // --- Payment ---

    fun findPaymentById(id: Long): Payment? =
        dsl.selectFrom(PAYMENTS)
            .where(PAYMENTS.ID.eq(id))
            .fetchOne()
            ?.toModel()

    fun findPaymentByIdempotencyKey(key: String): Payment? =
        dsl.selectFrom(PAYMENTS)
            .where(PAYMENTS.IDEMPOTENCY_KEY.eq(key))
            .fetchOne()
            ?.toModel()

    fun findPaymentByStripePaymentIntentId(paymentIntentId: String): Payment? =
        dsl.selectFrom(PAYMENTS)
            .where(PAYMENTS.STRIPE_PAYMENT_INTENT_ID.eq(paymentIntentId))
            .fetchOne()
            ?.toModel()

    /** A payment that hasn't reached a terminal state — blocks new pay attempts. */
    fun findInFlightPaymentByChargeId(rentChargeId: Long): Payment? =
        dsl.selectFrom(PAYMENTS)
            .where(PAYMENTS.RENT_CHARGE_ID.eq(rentChargeId))
            .and(PAYMENTS.STATUS.`in`(
                PaymentStatus.INITIATED.name,
                PaymentStatus.REQUIRES_ACTION.name,
                PaymentStatus.PROCESSING.name,
            ))
            .fetchOne()
            ?.toModel()

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

    /** All payments on charges belonging to the tenant's leases. */
    fun findPaymentsByTenantIdCursor(tenantId: Long, startAfterId: Long?, limit: Int): List<Payment> =
        dsl.select(PAYMENTS.fields().toList())
            .from(PAYMENTS)
            .join(RENT_CHARGES).on(PAYMENTS.RENT_CHARGE_ID.eq(RENT_CHARGES.ID))
            .join(LEASES).on(RENT_CHARGES.LEASE_ID.eq(LEASES.ID))
            .where(LEASES.TENANT_ID.eq(tenantId))
            .and(paymentCursorCondition(startAfterId))
            .orderBy(PAYMENTS.ID)
            .limit(limit)
            .fetchInto(PAYMENTS)
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
        failureReason: String? = null,
        stripePaymentIntentId: String? = null,
    ): Payment {
        dsl.update(PAYMENTS)
            .set(DSL.field(PAYMENTS.STATUS.unqualifiedName, PAYMENTS.STATUS.dataType), status.name)
            .apply {
                if (failureReason != null) {
                    set(DSL.field(PAYMENTS.FAILURE_REASON.unqualifiedName, PAYMENTS.FAILURE_REASON.dataType), failureReason)
                }
                if (stripePaymentIntentId != null) {
                    set(DSL.field(PAYMENTS.STRIPE_PAYMENT_INTENT_ID.unqualifiedName, PAYMENTS.STRIPE_PAYMENT_INTENT_ID.dataType), stripePaymentIntentId)
                }
            }
            .where(PAYMENTS.ID.eq(id))
            .execute()
        return findPaymentById(id)!!
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
