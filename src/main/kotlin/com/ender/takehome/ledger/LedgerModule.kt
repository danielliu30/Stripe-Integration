package com.ender.takehome.ledger

import com.ender.takehome.card.CardDataAccess
import com.ender.takehome.config.TransactionHelper
import com.ender.takehome.config.UserPrincipal
import com.ender.takehome.dto.request.RecordPaymentRequest
import com.ender.takehome.dto.response.CursorPage
import com.ender.takehome.exception.ConflictException
import com.ender.takehome.exception.ResourceNotFoundException
import com.ender.takehome.exception.UpstreamException
import com.ender.takehome.leasing.LeaseDataAccess
import com.ender.takehome.model.Card
import com.ender.takehome.model.Lease
import com.ender.takehome.model.Payment
import com.ender.takehome.model.PaymentMethod
import com.ender.takehome.model.PaymentStatus
import com.ender.takehome.model.RentCharge
import com.ender.takehome.model.RentChargeStatus
import com.ender.takehome.stripe.StripeChargeResult
import com.ender.takehome.stripe.StripeService
import com.ender.takehome.tenant.TenantDataAccess
import com.stripe.exception.CardException
import com.stripe.exception.StripeException
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.LocalDate

/** A payment plus the display data its API response needs. */
data class PaymentResult(
    val payment: Payment,
    val card: Card?,
    val clientSecret: String? = null,
)

@Service
class LedgerModule(
    private val dataAccess: LedgerDataAccess,
    private val leaseDataAccess: LeaseDataAccess,
    private val cardDataAccess: CardDataAccess,
    private val tenantDataAccess: TenantDataAccess,
    private val stripeService: StripeService,
    private val tx: TransactionHelper,
) {
    private val log = LoggerFactory.getLogger(LedgerModule::class.java)

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

    // --- Payments ---

    fun getPaymentById(id: Long, principal: UserPrincipal): PaymentResult {
        val payment = dataAccess.findPaymentById(id)
            ?: throw ResourceNotFoundException("Payment not found: $id")
        assertChargeOwnedByTenant(payment.rentChargeId, principal.tenantId, "Payment not found: $id")
        return toResult(payment)
    }

    fun getPaymentsByRentChargeId(rentChargeId: Long, startAfterId: Long?, limit: Int, principal: UserPrincipal): CursorPage<Payment> {
        // Charge must exist and (for tenants) belong to the caller — 404 either way
        assertChargeOwnedByTenant(rentChargeId, principal.tenantId, "Rent charge not found: $rentChargeId")

        val sanitized = CursorPage.sanitizeLimit(limit)
        val items = dataAccess.findPaymentsByRentChargeIdCursor(rentChargeId, startAfterId, sanitized + 1)
        return CursorPage.of(items, sanitized)
    }

    /** Role-scoped history: tenants see payments on their own leases, PMs see everything. */
    fun getPayments(startAfterId: Long?, limit: Int, principal: UserPrincipal): CursorPage<Payment> {
        val sanitized = CursorPage.sanitizeLimit(limit)
        val items = if (principal.tenantId != null) {
            dataAccess.findPaymentsByTenantIdCursor(principal.tenantId, startAfterId, sanitized + 1)
        } else {
            dataAccess.findAllPaymentsCursor(startAfterId, sanitized + 1)
        }
        return CursorPage.of(items, sanitized)
    }

    /** Batch-resolves the cards referenced by a page of payments (avoids N+1). */
    fun resolveCards(payments: List<Payment>): Map<Long, Card> {
        val cardIds = payments.mapNotNull { it.cardId }
        if (cardIds.isEmpty()) return emptyMap()
        return cardDataAccess.findByIds(cardIds).associateBy { it.id }
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

    /**
     * Charges one of the tenant's saved cards for the remaining balance of a
     * rent charge they own. Shape:
     *
     *   tx1: validate + FOR UPDATE lock the charge + INSERT payment (INITIATED)
     *   — Stripe call happens outside any DB transaction —
     *   tx2: apply the PaymentIntent result (status / charge -> PAID)
     *
     * Concurrent attempts serialize on the charge row lock; a retry with the
     * same Idempotency-Key returns the original payment (unique constraint is
     * the backstop for the check-then-insert race).
     */
    fun payCharge(principal: UserPrincipal, chargeId: Long, cardId: Long, idempotencyKey: String?): PaymentResult {
        val tenantId = principal.tenantId
            ?: throw ResourceNotFoundException("Rent charge not found: $chargeId")

        // Idempotent replay — returns the original payment instead of re-charging
        idempotencyKey?.let { key ->
            dataAccess.findPaymentByIdempotencyKey(key)?.let {
                assertChargeOwnedByTenant(it.rentChargeId, tenantId, "Rent charge not found: $chargeId")
                return toResult(it)
            }
        }

        val prepared = try {
            tx.executeWithRetry { preparePayment(tenantId, principal.email, chargeId, cardId, idempotencyKey) }
        } catch (e: DataIntegrityViolationException) {
            // Lost the idempotency-key insert race — the winner's row is committed; replay it
            val existing = idempotencyKey?.let { dataAccess.findPaymentByIdempotencyKey(it) }
                ?: throw e
            return toResult(existing)
        }

        val result = try {
            stripeService.chargeCard(
                customerId = prepared.stripeCustomerId,
                paymentMethodId = prepared.card.stripePaymentMethodId,
                amount = prepared.payment.amount,
                idempotencyKey = idempotencyKey ?: "payment-${prepared.payment.id}",
                metadata = mapOf(
                    "paymentId" to prepared.payment.id.toString(),
                    "rentChargeId" to chargeId.toString(),
                ),
            )
        } catch (e: CardException) {
            // A decline is a business outcome, not an upstream failure — return
            // the FAILED payment with Stripe's reason so the client can prompt
            // for a different card. The PI id lets the webhook reconcile later.
            log.info("Card declined for payment {}: {}", prepared.payment.id, e.stripeError?.declineCode)
            val settled = tx.executeWithRetry {
                settlePayment(
                    prepared.payment.id,
                    PaymentStatus.FAILED,
                    e.stripeError?.message ?: e.message ?: "Card declined",
                    e.stripeError?.paymentIntent?.id,
                )
            }
            return PaymentResult(settled, prepared.card)
        } catch (e: StripeException) {
            log.error("Stripe charge failed for payment {}", prepared.payment.id, e)
            tx.executeWithRetry {
                dataAccess.updatePaymentStatus(prepared.payment.id, PaymentStatus.FAILED, "Payment processor error")
            }
            throw UpstreamException("Payment processor unavailable", e)
        }

        val settled = tx.executeWithRetry {
            settlePayment(prepared.payment.id, result.status, result.failureReason, result.paymentIntentId)
        }
        return PaymentResult(settled, prepared.card, result.clientSecret)
    }

    private data class PreparedPayment(
        val payment: Payment,
        val card: Card,
        val stripeCustomerId: String,
    )

    /** Runs inside tx1 — validation + charge lock + INITIATED insert. */
    private fun preparePayment(
        tenantId: Long,
        recordedBy: String,
        chargeId: Long,
        cardId: Long,
        idempotencyKey: String?,
    ): PreparedPayment {
        val charge = dataAccess.findChargeByIdForUpdate(chargeId)
            ?: throw ResourceNotFoundException("Rent charge not found: $chargeId")
        assertChargeOwned(charge, tenantId, "Rent charge not found: $chargeId")

        if (charge.status == RentChargeStatus.PAID) {
            throw ConflictException("Rent charge $chargeId is already paid")
        }
        if (dataAccess.findInFlightPaymentByChargeId(chargeId) != null) {
            throw ConflictException("A payment for rent charge $chargeId is already in progress")
        }

        val card = cardDataAccess.findById(cardId)
        if (card == null || card.tenantId != tenantId) {
            throw ResourceNotFoundException("Card not found: $cardId")
        }
        val tenant = tenantDataAccess.findById(tenantId)
            ?: throw ResourceNotFoundException("Tenant not found: $tenantId")
        val customerId = tenant.stripeCustomerId
            ?: throw IllegalStateException("Tenant $tenantId has a card but no Stripe customer")

        // Server-derived amount — the client only picks the payment method
        val amount = charge.amount.subtract(dataAccess.sumSucceededPayments(chargeId))
        if (amount <= BigDecimal.ZERO) {
            throw ConflictException("Rent charge $chargeId is already paid")
        }

        val payment = dataAccess.savePayment(
            Payment(
                rentChargeId = chargeId,
                amount = amount,
                paymentMethod = PaymentMethod.CREDIT_CARD,
                status = PaymentStatus.INITIATED,
                cardId = cardId,
                idempotencyKey = idempotencyKey,
                recordedBy = recordedBy,
            )
        )
        return PreparedPayment(payment, card, customerId)
    }

    /**
     * Applies a Stripe-observed status to the payment matching
     * [paymentIntentId] (webhook path). Idempotent and monotonic — events that
     * would regress a terminal status are ignored.
     */
    @Transactional
    fun applyStripeEvent(paymentIntentId: String, status: PaymentStatus, failureReason: String? = null) {
        val payment = dataAccess.findPaymentByStripePaymentIntentId(paymentIntentId)
        if (payment == null) {
            log.warn("Stripe event for unknown payment intent: {}", paymentIntentId)
            return
        }
        if (status !in allowedTransitions.getValue(payment.status)) {
            log.info("Ignoring {} event for payment {} in status {}", status, payment.id, payment.status)
            return
        }
        settlePayment(payment.id, status, failureReason)
    }

    /** Transitions a payment and flips its charge to PAID on success. Caller holds the tx. */
    private fun settlePayment(
        paymentId: Long,
        status: PaymentStatus,
        failureReason: String?,
        stripePaymentIntentId: String? = null,
    ): Payment {
        val updated = dataAccess.updatePaymentStatus(paymentId, status, failureReason, stripePaymentIntentId)
        when (status) {
            PaymentStatus.SUCCEEDED -> {
                val charge = dataAccess.findChargeById(updated.rentChargeId)!!
                dataAccess.saveCharge(charge.copy(status = RentChargeStatus.PAID))
            }
            // Money returned to the tenant — the charge is owed again
            PaymentStatus.REFUNDED -> {
                val charge = dataAccess.findChargeById(updated.rentChargeId)!!
                dataAccess.saveCharge(charge.copy(status = RentChargeStatus.PENDING))
            }
            else -> {}
        }
        return updated
    }

    private fun toResult(payment: Payment): PaymentResult =
        PaymentResult(payment, payment.cardId?.let { cardDataAccess.findById(it) })

    /** 404 (not 403) when the charge doesn't exist or isn't the tenant's — no existence leak. */
    private fun assertChargeOwnedByTenant(rentChargeId: Long, tenantId: Long?, notFoundMessage: String) {
        if (tenantId == null) return // PM — sees everything
        val charge = dataAccess.findChargeById(rentChargeId)
            ?: throw ResourceNotFoundException(notFoundMessage)
        assertChargeOwned(charge, tenantId, notFoundMessage)
    }

    private fun assertChargeOwned(charge: RentCharge, tenantId: Long, notFoundMessage: String) {
        val lease = leaseDataAccess.findById(charge.leaseId)
        if (lease == null || lease.tenantId != tenantId) {
            throw ResourceNotFoundException(notFoundMessage)
        }
    }

    private companion object {
        val allowedTransitions: Map<PaymentStatus, Set<PaymentStatus>> = mapOf(
            PaymentStatus.INITIATED to setOf(PaymentStatus.REQUIRES_ACTION, PaymentStatus.PROCESSING, PaymentStatus.SUCCEEDED, PaymentStatus.FAILED),
            PaymentStatus.REQUIRES_ACTION to setOf(PaymentStatus.PROCESSING, PaymentStatus.SUCCEEDED, PaymentStatus.FAILED),
            PaymentStatus.PROCESSING to setOf(PaymentStatus.SUCCEEDED, PaymentStatus.FAILED),
            PaymentStatus.SUCCEEDED to setOf(PaymentStatus.REFUNDED),
            PaymentStatus.FAILED to emptySet(),
            PaymentStatus.REFUNDED to emptySet(),
        )
    }
}
