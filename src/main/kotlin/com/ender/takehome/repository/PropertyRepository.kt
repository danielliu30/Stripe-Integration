package com.ender.takehome.repository

import com.ender.takehome.generated.tables.Properties.PROPERTIES
import com.ender.takehome.generated.tables.records.PropertiesRecord
import com.ender.takehome.model.Property
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Component
import java.time.ZoneOffset

@Component
class PropertyRepository(private val dsl: DSLContext) {

    fun findById(id: Long): Property? =
        dsl.selectFrom(PROPERTIES)
            .where(PROPERTIES.ID.eq(id))
            .fetchOne()
            ?.toModel()

    fun findAllCursor(startAfterId: Long?, limit: Int): List<Property> =
        dsl.selectFrom(PROPERTIES)
            .where(cursorCondition(startAfterId))
            .orderBy(PROPERTIES.ID)
            .limit(limit)
            .fetch()
            .map { it.toModel() }

    fun save(property: Property): Property {
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

    private fun cursorCondition(startAfterId: Long?) =
        if (startAfterId != null) PROPERTIES.ID.gt(startAfterId) else DSL.noCondition()

    private fun PropertiesRecord.toModel() = Property(
        id = id!!,
        pmId = pmId!!,
        name = name!!,
        address = address!!,
        createdAt = createdAt!!.toInstant(ZoneOffset.UTC),
    )
}
