package com.ender.takehome.repository

import com.ender.takehome.model.Property
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository

interface PropertyRepository : JpaRepository<Property, Long> {

    @EntityGraph(attributePaths = ["propertyManager"])
    override fun findAll(pageable: Pageable): Page<Property>

    @EntityGraph(attributePaths = ["propertyManager"])
    fun findByPropertyManagerId(pmId: Long, pageable: Pageable): Page<Property>
}
