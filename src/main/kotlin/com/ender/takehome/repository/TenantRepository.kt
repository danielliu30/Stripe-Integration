package com.ender.takehome.repository

import com.ender.takehome.model.Tenant
import org.springframework.data.jpa.repository.JpaRepository

interface TenantRepository : JpaRepository<Tenant, Long> {
    fun findByEmail(email: String): Tenant?
}
