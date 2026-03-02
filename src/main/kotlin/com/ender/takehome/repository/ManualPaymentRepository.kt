package com.ender.takehome.repository

import com.ender.takehome.generated.tables.ManualPayments.MANUAL_PAYMENTS
import com.ender.takehome.generated.tables.records.ManualPaymentsRecord
import com.ender.takehome.model.ManualPayment
import com.ender.takehome.model.PaymentMethod
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Component
import java.time.ZoneOffset

@Component
class ManualPaymentRepository(private val dsl: DSLContext) {

    fun findByRentChargeIdCursor(rentChargeId: Long, startAfterId: Long?, limit: Int): List<ManualPayment> =
        dsl.selectFrom(MANUAL_PAYMENTS)
            .where(MANUAL_PAYMENTS.RENT_CHARGE_ID.eq(rentChargeId))
            .and(cursorCondition(startAfterId))
            .orderBy(MANUAL_PAYMENTS.ID)
            .limit(limit)
            .fetch()
            .map { it.toModel() }

    fun save(payment: ManualPayment): ManualPayment {
        if (payment.id == 0L) {
            val record = dsl.newRecord(MANUAL_PAYMENTS).apply {
                rentChargeId = payment.rentChargeId
                amount = payment.amount
                paymentMethod = payment.paymentMethod.name
                notes = payment.notes
                recordedBy = payment.recordedBy
            }
            record.store()
            return payment.copy(id = record.id!!)
        }
        return payment
    }

    private fun cursorCondition(startAfterId: Long?) =
        if (startAfterId != null) MANUAL_PAYMENTS.ID.gt(startAfterId) else DSL.noCondition()

    private fun ManualPaymentsRecord.toModel() = ManualPayment(
        id = id!!,
        rentChargeId = rentChargeId!!,
        amount = amount!!,
        paymentMethod = PaymentMethod.valueOf(paymentMethod!!),
        notes = notes,
        recordedBy = recordedBy!!,
        createdAt = createdAt!!.toInstant(ZoneOffset.UTC),
    )
}
