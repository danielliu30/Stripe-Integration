package com.ender.takehome.property

import com.ender.takehome.generated.tables.Properties.PROPERTIES
import com.ender.takehome.generated.tables.PropertyManagers.PROPERTY_MANAGERS
import com.ender.takehome.generated.tables.Units.UNITS
import com.ender.takehome.generated.tables.records.PropertiesRecord
import com.ender.takehome.generated.tables.records.PropertyManagersRecord
import com.ender.takehome.generated.tables.records.UnitsRecord
import com.ender.takehome.model.Property
import com.ender.takehome.model.PropertyManager
import com.ender.takehome.model.PropertyUnit
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Component
import java.time.ZoneOffset

@Component
class PropertyDataAccess(private val dsl: DSLContext) {

    // --- Property Manager ---

    fun findPropertyManagerById(id: Long): PropertyManager? =
        dsl.selectFrom(PROPERTY_MANAGERS)
            .where(PROPERTY_MANAGERS.ID.eq(id))
            .fetchOne()
            ?.toModel()

    // --- Property ---

    fun findPropertyById(id: Long): Property? =
        dsl.selectFrom(PROPERTIES)
            .where(PROPERTIES.ID.eq(id))
            .fetchOne()
            ?.toModel()

    fun findAllPropertiesCursor(startAfterId: Long?, limit: Int): List<Property> =
        dsl.selectFrom(PROPERTIES)
            .where(propertyCursorCondition(startAfterId))
            .orderBy(PROPERTIES.ID)
            .limit(limit)
            .fetch()
            .map { it.toModel() }

    fun saveProperty(property: Property): Property {
        if (property.id == 0L) {
            val record = dsl.newRecord(PROPERTIES).apply {
                pmId = property.pmId
                name = property.name
                address = property.address
            }
            record.store()
            return property.copy(id = record.id!!)
        }
        dsl.update(PROPERTIES)
            .set(PROPERTIES.NAME, property.name)
            .set(PROPERTIES.ADDRESS, property.address)
            .where(PROPERTIES.ID.eq(property.id))
            .execute()
        return property
    }

    // --- Unit ---

    fun findUnitById(id: Long): PropertyUnit? =
        dsl.selectFrom(UNITS)
            .where(UNITS.ID.eq(id))
            .fetchOne()
            ?.toModel()

    fun findUnitsByPropertyIdCursor(propertyId: Long, startAfterId: Long?, limit: Int): List<PropertyUnit> =
        dsl.selectFrom(UNITS)
            .where(UNITS.PROPERTY_ID.eq(propertyId))
            .and(unitCursorCondition(startAfterId))
            .orderBy(UNITS.ID)
            .limit(limit)
            .fetch()
            .map { it.toModel() }

    fun saveUnit(unit: PropertyUnit): PropertyUnit {
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

    // --- Cursor helpers ---

    private fun propertyCursorCondition(startAfterId: Long?) =
        if (startAfterId != null) PROPERTIES.ID.gt(startAfterId) else DSL.noCondition()

    private fun unitCursorCondition(startAfterId: Long?) =
        if (startAfterId != null) UNITS.ID.gt(startAfterId) else DSL.noCondition()

    // --- Record mappers ---

    private fun PropertiesRecord.toModel() = Property(
        id = id!!,
        pmId = pmId!!,
        name = name!!,
        address = address!!,
        createdAt = createdAt!!.toInstant(ZoneOffset.UTC),
    )

    private fun PropertyManagersRecord.toModel() = PropertyManager(
        id = id!!,
        name = name!!,
        email = email!!,
        createdAt = createdAt!!.toInstant(ZoneOffset.UTC),
    )

    private fun UnitsRecord.toModel() = PropertyUnit(
        id = id!!,
        propertyId = propertyId!!,
        unitNumber = unitNumber!!,
        createdAt = createdAt!!.toInstant(ZoneOffset.UTC),
    )
}
