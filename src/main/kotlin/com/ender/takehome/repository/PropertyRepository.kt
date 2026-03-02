package com.ender.takehome.repository

import com.ender.takehome.model.Property
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface PropertyRepository : JpaRepository<Property, Long> {

    @EntityGraph(attributePaths = ["propertyManager"])
    @Query("SELECT p FROM Property p WHERE (:startAfterId IS NULL OR p.id > :startAfterId) ORDER BY p.id")
    fun findAllCursor(startAfterId: Long?, pageable: Pageable): List<Property>
}
