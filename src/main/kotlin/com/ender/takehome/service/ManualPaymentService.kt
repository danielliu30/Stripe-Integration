package com.ender.takehome.service

import com.ender.takehome.dto.request.RecordManualPaymentRequest
import com.ender.takehome.model.ManualPayment
import com.ender.takehome.repository.ManualPaymentRepository
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ManualPaymentService(
    private val manualPaymentRepository: ManualPaymentRepository,
    private val rentChargeService: RentChargeService,
) {

    fun getByRentChargeId(rentChargeId: Long, pageable: Pageable): Page<ManualPayment> =
        manualPaymentRepository.findByRentChargeId(rentChargeId, pageable)

    @Transactional
    fun recordPayment(request: RecordManualPaymentRequest): ManualPayment {
        val rentCharge = rentChargeService.getById(request.rentChargeId)

        val payment = ManualPayment(
            rentCharge = rentCharge,
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
