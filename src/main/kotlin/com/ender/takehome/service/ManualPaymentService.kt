package com.ender.takehome.service

import com.ender.takehome.dto.request.RecordManualPaymentRequest
import com.ender.takehome.dto.response.CursorPage
import com.ender.takehome.model.ManualPayment
import com.ender.takehome.repository.ManualPaymentRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ManualPaymentService(
    private val manualPaymentRepository: ManualPaymentRepository,
    private val rentChargeService: RentChargeService,
) {

    fun getByRentChargeId(rentChargeId: Long, startAfterId: Long?, limit: Int): CursorPage<ManualPayment> {
        val sanitized = CursorPage.sanitizeLimit(limit)
        val items = manualPaymentRepository.findByRentChargeIdCursor(rentChargeId, startAfterId, sanitized + 1)
        return CursorPage.of(items, sanitized)
    }

    @Transactional
    fun recordPayment(request: RecordManualPaymentRequest): ManualPayment {
        val rentCharge = rentChargeService.getById(request.rentChargeId)

        val payment = ManualPayment(
            rentChargeId = rentCharge.id,
            amount = request.amount,
            paymentMethod = request.paymentMethod,
            notes = request.notes,
            recordedBy = request.recordedBy,
        )
        val saved = manualPaymentRepository.save(payment)

        rentChargeService.markPaid(rentCharge.id)

        return saved
    }
}
