package com.ender.takehome.repository

import com.ender.takehome.model.Tenant
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface TenantRepository : JpaRepository<Tenant, Long> {

    fun findByEmail(email: String): Tenant?

    @Query("SELECT t FROM Tenant t WHERE (:startAfterId IS NULL OR t.id > :startAfterId) ORDER BY t.id")
    fun findAllCursor(startAfterId: Long?, pageable: Pageable): List<Tenant>
}
