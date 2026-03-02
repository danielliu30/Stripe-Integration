package com.ender.takehome.repository

import com.ender.takehome.model.RentCharge
import com.ender.takehome.model.RentChargeStatus
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.time.LocalDate

interface RentChargeRepository : JpaRepository<RentCharge, Long> {

    @EntityGraph(attributePaths = ["lease"])
    @Query("SELECT rc FROM RentCharge rc WHERE rc.lease.id = :leaseId AND (:startAfterId IS NULL OR rc.id > :startAfterId) ORDER BY rc.id")
    fun findByLeaseIdCursor(leaseId: Long, startAfterId: Long?, pageable: Pageable): List<RentCharge>

    @EntityGraph(attributePaths = ["lease"])
    @Query("SELECT rc FROM RentCharge rc WHERE rc.lease.id = :leaseId AND rc.status = :status AND (:startAfterId IS NULL OR rc.id > :startAfterId) ORDER BY rc.id")
    fun findByLeaseIdAndStatusCursor(leaseId: Long, status: RentChargeStatus, startAfterId: Long?, pageable: Pageable): List<RentCharge>

    fun findByLeaseIdAndDueDate(leaseId: Long, dueDate: LocalDate): RentCharge?

    fun findByStatus(status: RentChargeStatus): List<RentCharge>
}
