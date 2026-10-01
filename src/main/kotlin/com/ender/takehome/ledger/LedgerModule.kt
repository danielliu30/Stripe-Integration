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
import com.ender.takehome.stripe.StripePaymentService
import com.ender.takehome.tenant.TenantDataAccess
import com.stripe.exception.CardException
import com.stripe.exception.StripeException
import org.jooq.exception.IntegrityConstraintViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.LocalDate

/** A persisted payment plus the safe card details needed by the API response. */
data class PaymentResult(
    val payment: Payment,
    val card: Card?,
)

@Service
class LedgerModule(
    private val dataAccess: LedgerDataAccess,
    private val cardDataAccess: CardDataAccess,
    private val leaseDataAccess: LeaseDataAccess,
    private val tenantDataAccess: TenantDataAccess,
    private val stripePaymentService: StripePaymentService,
    private val transactionHelper: TransactionHelper,
) {

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

    /** Returns tenant-owned payments for tenants and all payments for property managers. */
    fun getPayments(startAfterId: Long?, limit: Int, principal: UserPrincipal): CursorPage<Payment> {
        val sanitized = CursorPage.sanitizeLimit(limit)
        val payments = if (principal.tenantId != null) {
            dataAccess.findPaymentsByTenantIdCursor(principal.tenantId, startAfterId, sanitized + 1)
        } else {
            dataAccess.findAllPaymentsCursor(startAfterId, sanitized + 1)
        }
        return CursorPage.of(payments, sanitized)
    }

    /** Loads card display data in one query for a page of card payments. */
    fun resolveCards(payments: List<Payment>): Map<Long, Card> {
        val cardIds = payments.mapNotNull { it.cardId }.distinct()
        return cardDataAccess.findByIds(cardIds).associateBy { it.id }
    }

    fun getPaymentsByRentChargeId(rentChargeId: Long, startAfterId: Long?, limit: Int): CursorPage<Payment> {
        val sanitized = CursorPage.sanitizeLimit(limit)
        val items = dataAccess.findPaymentsByRentChargeIdCursor(rentChargeId, startAfterId, sanitized + 1)
        return CursorPage.of(items, sanitized)
    }

    /**
     * Pays the remaining balance of a tenant-owned rent charge with a tenant-owned saved card.
     *
     * Payment preparation runs in a short transaction that locks the rent charge, validates
     * ownership and state, calculates the server-controlled amount, and inserts an `INITIATED`
     * payment. The Stripe network call deliberately runs outside that transaction so a slow
     * dependency cannot hold a database lock. Settlement then runs in a second transaction.
     *
     * The client idempotency key is checked before preparation and stored on the payment. A
     * database uniqueness violation handles concurrent requests that both miss the first lookup.
     * Replays return the existing tenant-owned payment without contacting Stripe again.
     */
    fun payCharge(
        principal: UserPrincipal,
        chargeId: Long,
        cardId: Long,
        idempotencyKey: String,
    ): PaymentResult {
        val tenantId = principal.tenantId
            ?: throw ResourceNotFoundException("Rent charge not found: $chargeId")
        val replay = dataAccess.findPaymentByIdempotencyKey(idempotencyKey)
        if (replay != null) return replayPayment(replay, tenantId, chargeId)

        val prepared = try {
            transactionHelper.executeWithRetry {
                preparePayment(tenantId, principal.email, chargeId, cardId, idempotencyKey)
            }
        } catch (exception: IntegrityConstraintViolationException) {
            val existing = dataAccess.findPaymentByIdempotencyKey(idempotencyKey) ?: throw exception
            return replayPayment(existing, tenantId, chargeId)
        }

        val stripeResult = try {
            stripePaymentService.chargeCard(
                customerId = prepared.customerId,
                paymentMethodId = prepared.card.stripePaymentMethodId,
                amount = prepared.payment.amount,
                idempotencyKey = idempotencyKey,
                metadata = mapOf(
                    "paymentId" to prepared.payment.id.toString(),
                    "rentChargeId" to chargeId.toString(),
                ),
            )
        } catch (exception: CardException) {
            val failed = transactionHelper.executeWithRetry {
                dataAccess.updatePaymentStatus(
                    prepared.payment.id,
                    PaymentStatus.FAILED,
                    exception.stripeError?.message ?: exception.message ?: "Card declined",
                    exception.stripeError?.paymentIntent?.id,
                )
            }
            return PaymentResult(failed, prepared.card)
        } catch (exception: StripeException) {
            transactionHelper.executeWithRetry {
                dataAccess.updatePaymentStatus(prepared.payment.id, PaymentStatus.FAILED, "Payment processor error")
            }
            throw UpstreamException("Payment processor unavailable", exception)
        }

        val settled = transactionHelper.executeWithRetry {
            settlePayment(
                prepared.payment.id,
                stripeResult.status,
                stripeResult.failureReason,
                stripeResult.paymentIntentId,
            )
        }
        return PaymentResult(settled, prepared.card)
    }

    /**
     * Runs inside the preparation transaction while holding the rent-charge row lock.
     * No external Stripe operation may be added to this method.
     */
    private fun preparePayment(
        tenantId: Long,
        recordedBy: String,
        chargeId: Long,
        cardId: Long,
        idempotencyKey: String,
    ): PreparedPayment {
        val charge = dataAccess.findChargeByIdForUpdate(chargeId)
            ?: throw ResourceNotFoundException("Rent charge not found: $chargeId")
        val lease = leaseDataAccess.findById(charge.leaseId)
        if (lease == null || lease.tenantId != tenantId) {
            throw ResourceNotFoundException("Rent charge not found: $chargeId")
        }
        if (charge.status == RentChargeStatus.PAID) {
            throw ConflictException("Rent charge $chargeId is already paid")
        }
        if (dataAccess.findInFlightPaymentByChargeId(chargeId) != null) {
            throw ConflictException("A payment for rent charge $chargeId is already in progress")
        }
        val card = cardDataAccess.findActiveById(cardId)
        if (card == null || card.tenantId != tenantId) {
            throw ResourceNotFoundException("Card not found: $cardId")
        }
        val tenant = tenantDataAccess.findById(tenantId)
            ?: throw ResourceNotFoundException("Tenant not found: $tenantId")
        val customerId = tenant.stripeCustomerId
            ?: throw IllegalStateException("Tenant $tenantId has no Stripe customer")
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
     * Reconciles Stripe's asynchronous view of a PaymentIntent with its local payment.
     *
     * The payment row is locked before validating [allowedStripeTransitions], serializing duplicate
     * or concurrently delivered events across application instances. Repeated states and stale
     * events are no-ops, so a late failure cannot regress a succeeded payment. A successful event
     * also settles the rent charge in this transaction through [settlePayment].
     *
     * Unknown PaymentIntents are acknowledged without mutation because a Stripe account may contain
     * objects created by another environment. A full refund reopens the charge in the same
     * transaction so its outstanding balance cannot disagree with the payment lifecycle.
     */
    @Transactional
    fun applyStripePaymentEvent(paymentIntentId: String, status: PaymentStatus, failureReason: String? = null) {
        val payment = dataAccess.findPaymentByStripePaymentIntentIdForUpdate(paymentIntentId) ?: return
        if (status == payment.status || status !in allowedStripeTransitions.getValue(payment.status)) return
        settlePayment(payment.id, status, failureReason, paymentIntentId)
    }

    /** Applies Stripe's synchronous result and marks the rent charge paid only on success. */
    private fun settlePayment(
        paymentId: Long,
        status: PaymentStatus,
        failureReason: String?,
        paymentIntentId: String,
    ): Payment {
        val payment = dataAccess.updatePaymentStatus(paymentId, status, failureReason, paymentIntentId)
        when (status) {
            PaymentStatus.SUCCEEDED -> {
                val charge = requireNotNull(dataAccess.findChargeById(payment.rentChargeId))
                dataAccess.saveCharge(charge.copy(status = RentChargeStatus.PAID))
            }
            PaymentStatus.REFUNDED -> {
                val charge = requireNotNull(dataAccess.findChargeById(payment.rentChargeId))
                dataAccess.saveCharge(charge.copy(status = RentChargeStatus.PENDING))
            }
            else -> Unit
        }
        return payment
    }

    private fun replayPayment(payment: Payment, tenantId: Long, chargeId: Long): PaymentResult {
        if (payment.rentChargeId != chargeId) {
            throw ConflictException("Idempotency key was already used for another rent charge")
        }
        val charge = dataAccess.findChargeById(chargeId)
        val lease = if (charge != null) leaseDataAccess.findById(charge.leaseId) else null
        if (lease == null || lease.tenantId != tenantId) {
            throw ResourceNotFoundException("Rent charge not found: $chargeId")
        }
        return PaymentResult(payment, payment.cardId?.let { cardDataAccess.findById(it) })
    }

    private data class PreparedPayment(
        val payment: Payment,
        val card: Card,
        val customerId: String,
    )

    private companion object {
        /**
         * Forward-only Stripe transitions for one local payment attempt. `FAILED` is terminal for
         * this attempt; a later client retry creates a new local payment and PaymentIntent.
         * Only a succeeded payment can become fully refunded.
         */
        val allowedStripeTransitions = mapOf(
            PaymentStatus.INITIATED to setOf(
                PaymentStatus.REQUIRES_ACTION,
                PaymentStatus.PROCESSING,
                PaymentStatus.SUCCEEDED,
                PaymentStatus.FAILED,
            ),
            PaymentStatus.REQUIRES_ACTION to setOf(
                PaymentStatus.PROCESSING,
                PaymentStatus.SUCCEEDED,
                PaymentStatus.FAILED,
            ),
            PaymentStatus.PROCESSING to setOf(PaymentStatus.SUCCEEDED, PaymentStatus.FAILED),
            PaymentStatus.SUCCEEDED to setOf(PaymentStatus.REFUNDED),
            PaymentStatus.FAILED to emptySet(),
            PaymentStatus.REFUNDED to emptySet(),
        )
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
