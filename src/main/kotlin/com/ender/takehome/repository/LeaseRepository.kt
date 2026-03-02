package com.ender.takehome.repository

import com.ender.takehome.model.Lease
import com.ender.takehome.model.LeaseStatus
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository

interface LeaseRepository : JpaRepository<Lease, Long> {

    @EntityGraph(attributePaths = ["tenant", "unit"])
    override fun findAll(pageable: Pageable): Page<Lease>

    @EntityGraph(attributePaths = ["tenant", "unit"])
    fun findByTenantId(tenantId: Long, pageable: Pageable): Page<Lease>

    @EntityGraph(attributePaths = ["tenant", "unit"])
    fun findByUnitId(unitId: Long, pageable: Pageable): Page<Lease>

    fun findByStatus(status: LeaseStatus): List<Lease>
}
