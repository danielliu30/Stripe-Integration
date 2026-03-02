package com.ender.takehome.repository

import com.ender.takehome.model.RentCharge
import com.ender.takehome.model.RentChargeStatus
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate

interface RentChargeRepository : JpaRepository<RentCharge, Long> {

    @EntityGraph(attributePaths = ["lease"])
    fun findByLeaseId(leaseId: Long, pageable: Pageable): Page<RentCharge>

    fun findByLeaseIdAndDueDate(leaseId: Long, dueDate: LocalDate): RentCharge?

    fun findByStatus(status: RentChargeStatus): List<RentCharge>

    @EntityGraph(attributePaths = ["lease"])
    fun findByLeaseIdAndStatus(leaseId: Long, status: RentChargeStatus, pageable: Pageable): Page<RentCharge>
}
