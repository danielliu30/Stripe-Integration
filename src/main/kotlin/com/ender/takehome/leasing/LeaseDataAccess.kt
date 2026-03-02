package com.ender.takehome.leasing

import com.ender.takehome.generated.tables.Leases.LEASES
import com.ender.takehome.generated.tables.records.LeasesRecord
import com.ender.takehome.model.Lease
import com.ender.takehome.model.LeaseStatus
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Component
import java.time.ZoneOffset

@Component
class LeaseDataAccess(private val dsl: DSLContext) {

    fun findById(id: Long): Lease? =
        dsl.selectFrom(LEASES)
            .where(LEASES.ID.eq(id))
            .fetchOne()
            ?.toModel()

    fun findAllCursor(startAfterId: Long?, limit: Int): List<Lease> =
        dsl.selectFrom(LEASES)
            .where(cursorCondition(startAfterId))
            .orderBy(LEASES.ID)
            .limit(limit)
            .fetch()
            .map { it.toModel() }

    fun findByTenantIdCursor(tenantId: Long, startAfterId: Long?, limit: Int): List<Lease> =
        dsl.selectFrom(LEASES)
            .where(LEASES.TENANT_ID.eq(tenantId))
            .and(cursorCondition(startAfterId))
            .orderBy(LEASES.ID)
            .limit(limit)
            .fetch()
            .map { it.toModel() }

    fun findByStatus(status: LeaseStatus): List<Lease> =
        dsl.selectFrom(LEASES)
            .where(LEASES.STATUS.eq(status.name))
            .fetch()
            .map { it.toModel() }

    fun save(lease: Lease): Lease {
        if (lease.id == 0L) {
            val record = dsl.newRecord(LEASES).apply {
                tenantId = lease.tenantId
                unitId = lease.unitId
                rentAmount = lease.rentAmount
                startDate = lease.startDate
                endDate = lease.endDate
                status = lease.status.name
            }
            record.store()
            return lease.copy(id = record.id!!)
        }
        dsl.update(LEASES)
            .set(LEASES.RENT_AMOUNT, lease.rentAmount)
            .set(LEASES.STATUS, lease.status.name)
            .where(LEASES.ID.eq(lease.id))
            .execute()
        return lease
    }

    private fun cursorCondition(startAfterId: Long?) =
        if (startAfterId != null) LEASES.ID.gt(startAfterId) else DSL.noCondition()

    private fun LeasesRecord.toModel() = Lease(
        id = id!!,
        tenantId = tenantId!!,
        unitId = unitId!!,
        rentAmount = rentAmount!!,
        startDate = startDate!!,
        endDate = endDate!!,
        status = LeaseStatus.valueOf(status!!),
        createdAt = createdAt!!.toInstant(ZoneOffset.UTC),
    )
}
