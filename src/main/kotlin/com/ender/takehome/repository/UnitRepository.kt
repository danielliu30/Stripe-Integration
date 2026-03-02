package com.ender.takehome.repository

import com.ender.takehome.model.ApartmentUnit
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository

interface UnitRepository : JpaRepository<ApartmentUnit, Long> {

    @EntityGraph(attributePaths = ["property"])
    fun findByPropertyId(propertyId: Long, pageable: Pageable): Page<ApartmentUnit>
}
