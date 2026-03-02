package com.ender.takehome.repository

import com.ender.takehome.generated.tables.Units.UNITS
import com.ender.takehome.generated.tables.records.UnitsRecord
import com.ender.takehome.model.ApartmentUnit
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Component
import java.time.ZoneOffset

@Component
class UnitRepository(private val dsl: DSLContext) {

    fun findById(id: Long): ApartmentUnit? =
        dsl.selectFrom(UNITS)
            .where(UNITS.ID.eq(id))
            .fetchOne()
            ?.toModel()

    fun findByPropertyIdCursor(propertyId: Long, startAfterId: Long?, limit: Int): List<ApartmentUnit> =
        dsl.selectFrom(UNITS)
            .where(UNITS.PROPERTY_ID.eq(propertyId))
            .and(cursorCondition(startAfterId))
            .orderBy(UNITS.ID)
            .limit(limit)
            .fetch()
            .map { it.toModel() }

    fun save(unit: ApartmentUnit): ApartmentUnit {
        if (unit.id == 0L) {
            val record = dsl.newRecord(UNITS).apply {
                propertyId = unit.propertyId
                unitNumber = unit.unitNumber
            }
            record.store()
            return unit.copy(id = record.id!!)
        }
        dsl.update(UNITS)
            .set(UNITS.UNIT_NUMBER, unit.unitNumber)
            .where(UNITS.ID.eq(unit.id))
            .execute()
        return unit
    }

    private fun cursorCondition(startAfterId: Long?) =
        if (startAfterId != null) UNITS.ID.gt(startAfterId) else DSL.noCondition()

    private fun UnitsRecord.toModel() = ApartmentUnit(
        id = id!!,
        propertyId = propertyId!!,
        unitNumber = unitNumber!!,
        createdAt = createdAt!!.toInstant(ZoneOffset.UTC),
    )
}
