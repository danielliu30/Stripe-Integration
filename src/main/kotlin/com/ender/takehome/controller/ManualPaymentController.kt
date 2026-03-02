package com.ender.takehome.controller

import com.ender.takehome.dto.request.RecordManualPaymentRequest
import com.ender.takehome.dto.response.ManualPaymentResponse
import com.ender.takehome.service.ManualPaymentService
import jakarta.validation.Valid
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/manual-payments")
@PreAuthorize("hasRole('PROPERTY_MANAGER')")
class ManualPaymentController(private val manualPaymentService: ManualPaymentService) {

    @GetMapping(params = ["rentChargeId"])
    fun getByRentCharge(@RequestParam rentChargeId: Long, pageable: Pageable): Page<ManualPaymentResponse> =
        manualPaymentService.getByRentChargeId(rentChargeId, pageable).map { ManualPaymentResponse.from(it) }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun recordPayment(@Valid @RequestBody request: RecordManualPaymentRequest): ManualPaymentResponse =
        ManualPaymentResponse.from(manualPaymentService.recordPayment(request))
}
