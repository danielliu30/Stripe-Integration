package com.ender.takehome.ledger

import com.ender.takehome.config.UserPrincipal
import com.ender.takehome.dto.request.GenerateRentChargesRequest
import com.ender.takehome.dto.request.PayChargeRequest
import com.ender.takehome.dto.request.RecordPaymentRequest
import com.ender.takehome.dto.response.CursorPage
import com.ender.takehome.dto.response.PaymentResponse
import com.ender.takehome.dto.response.RentChargeResponse
import com.ender.takehome.model.RentChargeStatus
import com.ender.takehome.worker.BackgroundJobRequest
import com.ender.takehome.worker.BackgroundJobType
import com.ender.takehome.worker.JobPublisher
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*

@RestController
class LedgerApi(
    private val ledgerModule: LedgerModule,
    private val jobPublisher: JobPublisher,
) {

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
        val page = ledgerModule.getChargesByLeaseIdAndStatus(leaseId, status, startAfterId, limit)
        return CursorPage(page.content.map { RentChargeResponse.from(it) }, page.hasMore)
    }

    // --- Rent Charge Generation ---

    @PostMapping("/api/rent-charges/generate")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasRole('PROPERTY_MANAGER')")
    fun generateRentCharges(@RequestBody(required = false) request: GenerateRentChargesRequest?) {
        val params = mutableMapOf<String, Any>()
        request?.dueDate?.let { params["dueDate"] = it.toString() }
        jobPublisher.publish(BackgroundJobRequest(BackgroundJobType.GENERATE_RENT_CHARGES, params))
    }

    // --- Card payment (tenant) ---

    @PostMapping("/api/rent-charges/{id}/pay")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasRole('TENANT')")
    fun payCharge(
        @PathVariable id: Long,
        @Valid @RequestBody request: PayChargeRequest,
        @RequestHeader("Idempotency-Key", required = false) idempotencyKey: String?,
    ): PaymentResponse {
        val result = ledgerModule.payCharge(UserPrincipal.current(), id, request.cardId, idempotencyKey)
        return PaymentResponse.from(result.payment, result.card)
    }

    // --- Payments ---

    @GetMapping("/api/payments")
    fun listPayments(
        @RequestParam(required = false) startAfterId: Long?,
        @RequestParam(defaultValue = "20") limit: Int,
    ): CursorPage<PaymentResponse> {
        val page = ledgerModule.getPayments(startAfterId, limit, UserPrincipal.current())
        val cards = ledgerModule.resolveCards(page.content)
        return CursorPage(
            page.content.map { PaymentResponse.from(it, it.cardId?.let(cards::get)) },
            page.hasMore,
        )
    }

    @GetMapping("/api/payments/{id}")
    fun getPayment(@PathVariable id: Long): PaymentResponse {
        val result = ledgerModule.getPaymentById(id, UserPrincipal.current())
        return PaymentResponse.from(result.payment, result.card)
    }

    @GetMapping("/api/payments", params = ["rentChargeId"])
    fun getPaymentsByRentCharge(
        @RequestParam rentChargeId: Long,
        @RequestParam(required = false) startAfterId: Long?,
        @RequestParam(defaultValue = "20") limit: Int,
    ): CursorPage<PaymentResponse> {
        val page = ledgerModule.getPaymentsByRentChargeId(rentChargeId, startAfterId, limit, UserPrincipal.current())
        val cards = ledgerModule.resolveCards(page.content)
        return CursorPage(
            page.content.map { PaymentResponse.from(it, it.cardId?.let(cards::get)) },
            page.hasMore,
        )
    }

    @PostMapping("/api/payments")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('PROPERTY_MANAGER')")
    fun recordPayment(@Valid @RequestBody request: RecordPaymentRequest): PaymentResponse =
        PaymentResponse.from(ledgerModule.recordPayment(request))
}
