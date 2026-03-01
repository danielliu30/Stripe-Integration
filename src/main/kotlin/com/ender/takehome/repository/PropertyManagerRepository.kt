package com.ender.takehome.repository

import com.ender.takehome.model.PropertyManager
import org.springframework.data.jpa.repository.JpaRepository

interface PropertyManagerRepository : JpaRepository<PropertyManager, Long>
