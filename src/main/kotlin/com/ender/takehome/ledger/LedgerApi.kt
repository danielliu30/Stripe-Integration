package com.ender.takehome.ledger

import com.ender.takehome.dto.request.RecordPaymentRequest
import com.ender.takehome.dto.response.CursorPage
import com.ender.takehome.dto.response.PaymentResponse
import com.ender.takehome.dto.response.RentChargeResponse
import com.ender.takehome.model.RentChargeStatus
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*

@RestController
class LedgerApi(private val ledgerModule: LedgerModule) {

    // --- Rent Charges ---

    @GetMapping("/api/rent-charges/{id}")
    fun getCharge(@PathVariable id: Long): RentChargeResponse =
        RentChargeResponse.from(ledgerModule.getChargeById(id))

    @GetMapping("/api/rent-charges", params = ["leaseId"])
    fun getChargesByLease(
        @RequestParam leaseId: Long,
        @RequestParam(required = false) startAfterId: Long?,
        @RequestParam(defaultValue = "20") limit: Int,
    ): CursorPage<RentChargeResponse> {
        val page = ledgerModule.getChargesByLeaseId(leaseId, startAfterId, limit)
        return CursorPage(page.content.map { RentChargeResponse.from(it) }, page.hasMore)
    }

    @GetMapping("/api/rent-charges", params = ["leaseId", "status"])
    fun getChargesByLeaseAndStatus(
        @RequestParam leaseId: Long,
        @RequestParam status: RentChargeStatus,
        @RequestParam(required = false) startAfterId: Long?,
        @RequestParam(defaultValue = "20") limit: Int,
    ): CursorPage<RentChargeResponse> {
        val page = if (status == RentChargeStatus.PENDING) {
            ledgerModule.getPendingChargesByLeaseId(leaseId, startAfterId, limit)
        } else {
            ledgerModule.getChargesByLeaseId(leaseId, startAfterId, limit)
        }
        return CursorPage(page.content.map { RentChargeResponse.from(it) }, page.hasMore)
    }

    // --- Manual Payments ---

    @GetMapping("/api/payments", params = ["rentChargeId"])
    @PreAuthorize("hasRole('PROPERTY_MANAGER')")
    fun getPaymentsByRentCharge(
        @RequestParam rentChargeId: Long,
        @RequestParam(required = false) startAfterId: Long?,
        @RequestParam(defaultValue = "20") limit: Int,
    ): CursorPage<PaymentResponse> {
        val page = ledgerModule.getPaymentsByRentChargeId(rentChargeId, startAfterId, limit)
        return CursorPage(page.content.map { PaymentResponse.from(it) }, page.hasMore)
    }

    @PostMapping("/api/payments")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('PROPERTY_MANAGER')")
    fun recordPayment(@Valid @RequestBody request: RecordPaymentRequest): PaymentResponse =
        PaymentResponse.from(ledgerModule.recordPayment(request))
}
