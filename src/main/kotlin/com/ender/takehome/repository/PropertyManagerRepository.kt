package com.ender.takehome.repository

import com.ender.takehome.generated.tables.PropertyManagers.PROPERTY_MANAGERS
import com.ender.takehome.generated.tables.records.PropertyManagersRecord
import com.ender.takehome.model.PropertyManager
import org.jooq.DSLContext
import org.springframework.stereotype.Component
import java.time.ZoneOffset

@Component
class PropertyManagerRepository(private val dsl: DSLContext) {

    fun findById(id: Long): PropertyManager? =
        dsl.selectFrom(PROPERTY_MANAGERS)
            .where(PROPERTY_MANAGERS.ID.eq(id))
            .fetchOne()
            ?.toModel()

    private fun PropertyManagersRecord.toModel() = PropertyManager(
        id = id!!,
        name = name!!,
        email = email!!,
        createdAt = createdAt!!.toInstant(ZoneOffset.UTC),
    )
}
