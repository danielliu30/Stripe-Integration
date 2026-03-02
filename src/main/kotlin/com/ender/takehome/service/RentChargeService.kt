package com.ender.takehome.service

import com.ender.takehome.exception.ResourceNotFoundException
import com.ender.takehome.model.Lease
import com.ender.takehome.model.RentCharge
import com.ender.takehome.model.RentChargeStatus
import com.ender.takehome.repository.RentChargeRepository
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

@Service
class RentChargeService(
    private val rentChargeRepository: RentChargeRepository,
) {

    fun getById(id: Long): RentCharge =
        rentChargeRepository.findById(id).orElseThrow { ResourceNotFoundException("Rent charge not found: $id") }

    fun getByLeaseId(leaseId: Long, pageable: Pageable): Page<RentCharge> =
        rentChargeRepository.findByLeaseId(leaseId, pageable)

    fun getPendingByLeaseId(leaseId: Long, pageable: Pageable): Page<RentCharge> =
        rentChargeRepository.findByLeaseIdAndStatus(leaseId, RentChargeStatus.PENDING, pageable)

    /**
     * Generate a rent charge for a lease for the given month.
     * Returns null if a charge already exists for that month.
     */
    @Transactional
    fun generateCharge(lease: Lease, dueDate: LocalDate): RentCharge? {
        val existing = rentChargeRepository.findByLeaseIdAndDueDate(lease.id, dueDate)
        if (existing != null) return null

        val charge = RentCharge(
            lease = lease,
            amount = lease.rentAmount,
            dueDate = dueDate,
        )
        return rentChargeRepository.save(charge)
    }

    @Transactional
    fun markPaid(chargeId: Long): RentCharge {
        val charge = getById(chargeId)
        charge.status = RentChargeStatus.PAID
        return rentChargeRepository.save(charge)
    }
}
