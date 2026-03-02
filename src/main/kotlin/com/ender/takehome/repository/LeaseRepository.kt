package com.ender.takehome.repository

import com.ender.takehome.model.Lease
import com.ender.takehome.model.LeaseStatus
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface LeaseRepository : JpaRepository<Lease, Long> {

    @EntityGraph(attributePaths = ["tenant", "unit"])
    @Query("SELECT l FROM Lease l WHERE (:startAfterId IS NULL OR l.id > :startAfterId) ORDER BY l.id")
    fun findAllCursor(startAfterId: Long?, pageable: Pageable): List<Lease>

    @EntityGraph(attributePaths = ["tenant", "unit"])
    @Query("SELECT l FROM Lease l WHERE l.tenant.id = :tenantId AND (:startAfterId IS NULL OR l.id > :startAfterId) ORDER BY l.id")
    fun findByTenantIdCursor(tenantId: Long, startAfterId: Long?, pageable: Pageable): List<Lease>

    fun findByStatus(status: LeaseStatus): List<Lease>
}
