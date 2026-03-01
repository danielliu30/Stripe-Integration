package com.ender.takehome.repository

import com.ender.takehome.model.ApartmentUnit
import org.springframework.data.jpa.repository.JpaRepository

interface UnitRepository : JpaRepository<ApartmentUnit, Long> {
    fun findByPropertyId(propertyId: Long): List<ApartmentUnit>
}
