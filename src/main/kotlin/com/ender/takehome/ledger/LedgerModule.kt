package com.ender.takehome.ledger

import com.ender.takehome.dto.request.RecordPaymentRequest
import com.ender.takehome.dto.response.CursorPage
import com.ender.takehome.exception.ResourceNotFoundException
import com.ender.takehome.model.Lease
import com.ender.takehome.model.Payment
import com.ender.takehome.model.RentCharge
import com.ender.takehome.model.RentChargeStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

@Service
class LedgerModule(private val dataAccess: LedgerDataAccess) {

    fun getChargeById(id: Long): RentCharge =
        dataAccess.findChargeById(id) ?: throw ResourceNotFoundException("Rent charge not found: $id")

    fun getChargesByLeaseId(leaseId: Long, startAfterId: Long?, limit: Int): CursorPage<RentCharge> {
        val sanitized = CursorPage.sanitizeLimit(limit)
        val items = dataAccess.findChargesByLeaseIdCursor(leaseId, startAfterId, sanitized + 1)
        return CursorPage.of(items, sanitized)
    }

    fun getPendingChargesByLeaseId(leaseId: Long, startAfterId: Long?, limit: Int): CursorPage<RentCharge> {
        val sanitized = CursorPage.sanitizeLimit(limit)
        val items = dataAccess.findChargesByLeaseIdAndStatusCursor(leaseId, RentChargeStatus.PENDING, startAfterId, sanitized + 1)
        return CursorPage.of(items, sanitized)
    }

    @Transactional
    fun generateCharge(lease: Lease, dueDate: LocalDate): RentCharge? {
        val existing = dataAccess.findChargeByLeaseIdAndDueDate(lease.id, dueDate)
        if (existing != null) return null

        val charge = RentCharge(
            leaseId = lease.id,
            amount = lease.rentAmount,
            dueDate = dueDate,
        )
        return dataAccess.saveCharge(charge)
    }

    fun getPaymentsByRentChargeId(rentChargeId: Long, startAfterId: Long?, limit: Int): CursorPage<Payment> {
        val sanitized = CursorPage.sanitizeLimit(limit)
        val items = dataAccess.findPaymentsByRentChargeIdCursor(rentChargeId, startAfterId, sanitized + 1)
        return CursorPage.of(items, sanitized)
    }

    @Transactional
    fun recordPayment(request: RecordPaymentRequest): Payment {
        val charge = dataAccess.findChargeById(request.rentChargeId)
            ?: throw ResourceNotFoundException("Rent charge not found: ${request.rentChargeId}")

        val payment = Payment(
            rentChargeId = charge.id,
            amount = request.amount,
            paymentMethod = request.paymentMethod,
            notes = request.notes,
            recordedBy = request.recordedBy,
        )
        val saved = dataAccess.savePayment(payment)

        dataAccess.saveCharge(charge.copy(status = RentChargeStatus.PAID))

        return saved
    }
}
