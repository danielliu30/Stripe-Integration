package com.ender.takehome.service

import com.ender.takehome.dto.response.CursorPage
import com.ender.takehome.exception.ResourceNotFoundException
import com.ender.takehome.model.Lease
import com.ender.takehome.model.RentCharge
import com.ender.takehome.model.RentChargeStatus
import com.ender.takehome.repository.RentChargeRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

@Service
class RentChargeService(
    private val rentChargeRepository: RentChargeRepository,
) {

    fun getById(id: Long): RentCharge =
        rentChargeRepository.findById(id) ?: throw ResourceNotFoundException("Rent charge not found: $id")

    fun getByLeaseId(leaseId: Long, startAfterId: Long?, limit: Int): CursorPage<RentCharge> {
        val sanitized = CursorPage.sanitizeLimit(limit)
        val items = rentChargeRepository.findByLeaseIdCursor(leaseId, startAfterId, sanitized + 1)
        return CursorPage.of(items, sanitized)
    }

    fun getPendingByLeaseId(leaseId: Long, startAfterId: Long?, limit: Int): CursorPage<RentCharge> {
        val sanitized = CursorPage.sanitizeLimit(limit)
        val items = rentChargeRepository.findByLeaseIdAndStatusCursor(leaseId, RentChargeStatus.PENDING, startAfterId, sanitized + 1)
        return CursorPage.of(items, sanitized)
    }

    @Transactional
    fun generateCharge(lease: Lease, dueDate: LocalDate): RentCharge? {
        val existing = rentChargeRepository.findByLeaseIdAndDueDate(lease.id, dueDate)
        if (existing != null) return null

        val charge = RentCharge(
            leaseId = lease.id,
            amount = lease.rentAmount,
            dueDate = dueDate,
        )
        return rentChargeRepository.save(charge)
    }

    @Transactional
    fun markPaid(chargeId: Long): RentCharge {
        val charge = getById(chargeId)
        val updated = charge.copy(status = RentChargeStatus.PAID)
        return rentChargeRepository.save(updated)
    }
}
