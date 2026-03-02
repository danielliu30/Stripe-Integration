package com.ender.takehome.repository

import com.ender.takehome.generated.tables.RentCharges.RENT_CHARGES
import com.ender.takehome.generated.tables.records.RentChargesRecord
import com.ender.takehome.model.RentCharge
import com.ender.takehome.model.RentChargeStatus
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.ZoneOffset

@Component
class RentChargeRepository(private val dsl: DSLContext) {

    fun findById(id: Long): RentCharge? =
        dsl.selectFrom(RENT_CHARGES)
            .where(RENT_CHARGES.ID.eq(id))
            .fetchOne()
            ?.toModel()

    fun findByLeaseIdCursor(leaseId: Long, startAfterId: Long?, limit: Int): List<RentCharge> =
        dsl.selectFrom(RENT_CHARGES)
            .where(RENT_CHARGES.LEASE_ID.eq(leaseId))
            .and(cursorCondition(startAfterId))
            .orderBy(RENT_CHARGES.ID)
            .limit(limit)
            .fetch()
            .map { it.toModel() }

    fun findByLeaseIdAndStatusCursor(leaseId: Long, status: RentChargeStatus, startAfterId: Long?, limit: Int): List<RentCharge> =
        dsl.selectFrom(RENT_CHARGES)
            .where(RENT_CHARGES.LEASE_ID.eq(leaseId))
            .and(RENT_CHARGES.STATUS.eq(status.name))
            .and(cursorCondition(startAfterId))
            .orderBy(RENT_CHARGES.ID)
            .limit(limit)
            .fetch()
            .map { it.toModel() }

    fun findByLeaseIdAndDueDate(leaseId: Long, dueDate: LocalDate): RentCharge? =
        dsl.selectFrom(RENT_CHARGES)
            .where(RENT_CHARGES.LEASE_ID.eq(leaseId))
            .and(RENT_CHARGES.DUE_DATE.eq(dueDate))
            .fetchOne()
            ?.toModel()

    fun save(charge: RentCharge): RentCharge {
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

    private fun cursorCondition(startAfterId: Long?) =
        if (startAfterId != null) RENT_CHARGES.ID.gt(startAfterId) else DSL.noCondition()

    private fun RentChargesRecord.toModel() = RentCharge(
        id = id!!,
        leaseId = leaseId!!,
        amount = amount!!,
        dueDate = dueDate!!,
        status = RentChargeStatus.valueOf(status!!),
        createdAt = createdAt!!.toInstant(ZoneOffset.UTC),
    )
}
