package com.ender.takehome.repository

import com.ender.takehome.model.Lease
import com.ender.takehome.model.LeaseStatus
import org.springframework.data.jpa.repository.JpaRepository

interface LeaseRepository : JpaRepository<Lease, Long> {
    fun findByTenantId(tenantId: Long): List<Lease>
    fun findByUnitId(unitId: Long): List<Lease>
    fun findByStatus(status: LeaseStatus): List<Lease>
}
