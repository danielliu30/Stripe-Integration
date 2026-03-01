package com.ender.takehome.repository

import com.ender.takehome.model.Property
import org.springframework.data.jpa.repository.JpaRepository

interface PropertyRepository : JpaRepository<Property, Long> {
    fun findByPropertyManagerId(pmId: Long): List<Property>
}
