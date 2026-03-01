package com.ender.takehome.repository

import com.ender.takehome.model.RentCharge
import com.ender.takehome.model.RentChargeStatus
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate

interface RentChargeRepository : JpaRepository<RentCharge, Long> {
    fun findByLeaseId(leaseId: Long): List<RentCharge>
    fun findByLeaseIdAndDueDate(leaseId: Long, dueDate: LocalDate): RentCharge?
    fun findByStatus(status: RentChargeStatus): List<RentCharge>
    fun findByLeaseIdAndStatus(leaseId: Long, status: RentChargeStatus): List<RentCharge>
}
