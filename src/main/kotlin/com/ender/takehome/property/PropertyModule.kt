package com.ender.takehome.property

import com.ender.takehome.dto.response.CursorPage
import com.ender.takehome.exception.ResourceNotFoundException
import com.ender.takehome.model.Property
import com.ender.takehome.model.PropertyUnit
import org.springframework.stereotype.Service

@Service
class PropertyModule(private val dataAccess: PropertyDataAccess) {

    fun getAllProperties(startAfterId: Long?, limit: Int): CursorPage<Property> {
        val sanitized = CursorPage.sanitizeLimit(limit)
        val items = dataAccess.findAllPropertiesCursor(startAfterId, sanitized + 1)
        return CursorPage.of(items, sanitized)
    }

    fun getProperty(id: Long): Property =
        dataAccess.findPropertyById(id)
            ?: throw ResourceNotFoundException("Property not found: $id")

    fun createProperty(pmId: Long, name: String, address: String): Property {
        dataAccess.findPropertyManagerById(pmId)
            ?: throw ResourceNotFoundException("Property manager not found: $pmId")
        val property = Property(pmId = pmId, name = name, address = address)
        return dataAccess.saveProperty(property)
    }

    fun getUnits(propertyId: Long, startAfterId: Long?, limit: Int): CursorPage<PropertyUnit> {
        dataAccess.findPropertyById(propertyId)
            ?: throw ResourceNotFoundException("Property not found: $propertyId")
        val sanitized = CursorPage.sanitizeLimit(limit)
        val items = dataAccess.findUnitsByPropertyIdCursor(propertyId, startAfterId, sanitized + 1)
        return CursorPage.of(items, sanitized)
    }

    fun createUnit(propertyId: Long, unitNumber: String): PropertyUnit {
        dataAccess.findPropertyById(propertyId)
            ?: throw ResourceNotFoundException("Property not found: $propertyId")
        val unit = PropertyUnit(propertyId = propertyId, unitNumber = unitNumber)
        return dataAccess.saveUnit(unit)
    }

    fun getUnit(id: Long): PropertyUnit =
        dataAccess.findUnitById(id)
            ?: throw ResourceNotFoundException("Unit not found: $id")
}
