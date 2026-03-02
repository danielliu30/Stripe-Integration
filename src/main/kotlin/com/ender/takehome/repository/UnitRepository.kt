package com.ender.takehome.repository

import com.ender.takehome.model.ApartmentUnit
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface UnitRepository : JpaRepository<ApartmentUnit, Long> {

    @EntityGraph(attributePaths = ["property"])
    @Query("SELECT u FROM ApartmentUnit u WHERE u.property.id = :propertyId AND (:startAfterId IS NULL OR u.id > :startAfterId) ORDER BY u.id")
    fun findByPropertyIdCursor(propertyId: Long, startAfterId: Long?, pageable: Pageable): List<ApartmentUnit>
}
