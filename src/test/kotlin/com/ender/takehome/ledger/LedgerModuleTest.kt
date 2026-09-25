package com.ender.takehome.ledger

import com.ender.takehome.TestFixtures
import com.ender.takehome.card.CardDataAccess
import com.ender.takehome.config.TransactionHelper
import com.ender.takehome.config.UserPrincipal
import com.ender.takehome.dto.request.RecordPaymentRequest
import com.ender.takehome.exception.ConflictException
import com.ender.takehome.exception.ResourceNotFoundException
import com.ender.takehome.exception.UpstreamException
import com.ender.takehome.leasing.LeaseDataAccess
import com.ender.takehome.model.PaymentMethod
import com.ender.takehome.model.PaymentStatus
import com.ender.takehome.model.RentChargeStatus
import com.ender.takehome.model.UserRole
import com.ender.takehome.stripe.StripeChargeResult
import com.ender.takehome.stripe.StripeService
import com.ender.takehome.tenant.TenantDataAccess
import com.stripe.exception.ApiException
import com.stripe.exception.CardException
import io.mockk.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.time.LocalDate

class LedgerModuleTest {

    private val dataAccess = mockk<LedgerDataAccess>()
    private val leaseDataAccess = mockk<LeaseDataAccess>()
    private val cardDataAccess = mockk<CardDataAccess>()
    private val tenantDataAccess = mockk<TenantDataAccess>()
    private val stripeService = mockk<StripeService>()
    private val tx = mockk<TransactionHelper>()
    private val module = LedgerModule(dataAccess, leaseDataAccess, cardDataAccess, tenantDataAccess, stripeService, tx)

    private val tenant = TestFixtures.tenant(id = 1).copy(stripeCustomerId = "cus_123")
    private val principal = UserPrincipal(2L, tenant.email, UserRole.TENANT, tenantId = 1L, pmId = null)
    private val lease = TestFixtures.lease(tenantId = 1)
    private val rentCharge = TestFixtures.rentCharge(leaseId = lease.id)
    private val card = TestFixtures.card(tenantId = 1)

    @BeforeEach
    fun setUp() {
        clearMocks(dataAccess, leaseDataAccess, cardDataAccess, tenantDataAccess, stripeService, tx)
        // TransactionHelper runs blocks inline in unit tests
        every { tx.executeWithRetry(any(), any(), any<() -> Any?>()) } answers {
            thirdArg<() -> Any?>().invoke()
        }
        every { dataAccess.findPaymentByIdempotencyKey(any()) } returns null
    }

    @Test
    fun `generateCharge creates new charge when none exists for that month`() {
        val dueDate = LocalDate.of(2025, 7, 1)
        every { dataAccess.findChargeByLeaseIdAndDueDate(lease.id, dueDate) } returns null
        every { dataAccess.saveCharge(any()) } answers { firstArg() }

        val result = module.generateCharge(lease, dueDate)

        assertNotNull(result)
        assertEquals(lease.rentAmount, result!!.amount)
        assertEquals(dueDate, result.dueDate)
        verify(exactly = 1) { dataAccess.saveCharge(any()) }
    }

    @Test
    fun `generateCharge returns null when charge already exists for that month`() {
        val dueDate = LocalDate.of(2025, 7, 1)
        val existing = TestFixtures.rentCharge(leaseId = lease.id, dueDate = dueDate)
        every { dataAccess.findChargeByLeaseIdAndDueDate(lease.id, dueDate) } returns existing

        val result = module.generateCharge(lease, dueDate)

        assertNull(result)
        verify(exactly = 0) { dataAccess.saveCharge(any()) }
    }

    @Test
    fun `recordPayment creates payment and marks charge as paid`() {
        val request = RecordPaymentRequest(
            rentChargeId = rentCharge.id,
            amount = BigDecimal("2000.00"),
            paymentMethod = PaymentMethod.CHECK,
            notes = "Check #1234",
            recordedBy = "admin@test.com",
        )

        every { dataAccess.findChargeById(rentCharge.id) } returns rentCharge
        every { dataAccess.savePayment(any()) } answers { firstArg() }
        every { dataAccess.saveCharge(any()) } answers { firstArg() }

        val result = module.recordPayment(request)

        assertEquals(BigDecimal("2000.00"), result.amount)
        assertEquals(PaymentMethod.CHECK, result.paymentMethod)
        assertEquals("Check #1234", result.notes)
        verify(exactly = 1) { dataAccess.saveCharge(match { it.status == RentChargeStatus.PAID }) }
    }

    // --- payCharge ---

    @Test
    fun `payCharge charges saved card and marks charge paid on success`() {
        val initiated = TestFixtures.payment(status = PaymentStatus.INITIATED)
        val settled = initiated.copy(status = PaymentStatus.SUCCEEDED, stripePaymentIntentId = "pi_123")

        every { dataAccess.findChargeByIdForUpdate(rentCharge.id) } returns rentCharge
        every { leaseDataAccess.findById(lease.id) } returns lease
        every { dataAccess.findInFlightPaymentByChargeId(rentCharge.id) } returns null
        every { cardDataAccess.findById(card.id) } returns card
        every { tenantDataAccess.findById(1L) } returns tenant
        every { dataAccess.sumSucceededPayments(rentCharge.id) } returns BigDecimal.ZERO
        every { dataAccess.savePayment(any()) } returns initiated
        every { stripeService.chargeCard("cus_123", card.stripePaymentMethodId, initiated.amount, any(), any()) } returns
            StripeChargeResult("pi_123", PaymentStatus.SUCCEEDED, null, null)
        every { dataAccess.updatePaymentStatus(initiated.id, PaymentStatus.SUCCEEDED, null, "pi_123") } returns settled
        every { dataAccess.findChargeById(rentCharge.id) } returns rentCharge
        every { dataAccess.saveCharge(any()) } answers { firstArg() }

        val result = module.payCharge(principal, rentCharge.id, card.id, "idem-1")

        assertEquals(PaymentStatus.SUCCEEDED, result.payment.status)
        assertEquals(card, result.card)
        verify(exactly = 1) {
            dataAccess.savePayment(match {
                it.status == PaymentStatus.INITIATED &&
                    it.paymentMethod == PaymentMethod.CREDIT_CARD &&
                    it.idempotencyKey == "idem-1"
            })
        }
        verify(exactly = 1) { dataAccess.saveCharge(match { it.status == RentChargeStatus.PAID }) }
    }

    @Test
    fun `payCharge throws 404 when charge belongs to another tenant`() {
        val foreignLease = TestFixtures.lease(id = 9, tenantId = 99)
        every { dataAccess.findChargeByIdForUpdate(rentCharge.id) } returns rentCharge.copy(leaseId = 9)
        every { leaseDataAccess.findById(9L) } returns foreignLease

        assertThrows<ResourceNotFoundException> {
            module.payCharge(principal, rentCharge.id, card.id, null)
        }
        verify(exactly = 0) { stripeService.chargeCard(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `payCharge throws 409 when charge already paid`() {
        every { dataAccess.findChargeByIdForUpdate(rentCharge.id) } returns rentCharge.copy(status = RentChargeStatus.PAID)
        every { leaseDataAccess.findById(lease.id) } returns lease

        assertThrows<ConflictException> {
            module.payCharge(principal, rentCharge.id, card.id, null)
        }
        verify(exactly = 0) { stripeService.chargeCard(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `payCharge throws 409 when a payment is already in flight`() {
        every { dataAccess.findChargeByIdForUpdate(rentCharge.id) } returns rentCharge
        every { leaseDataAccess.findById(lease.id) } returns lease
        every { dataAccess.findInFlightPaymentByChargeId(rentCharge.id) } returns
            TestFixtures.payment(status = PaymentStatus.PROCESSING)

        assertThrows<ConflictException> {
            module.payCharge(principal, rentCharge.id, card.id, null)
        }
        verify(exactly = 0) { stripeService.chargeCard(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `payCharge throws 404 when card belongs to another tenant`() {
        every { dataAccess.findChargeByIdForUpdate(rentCharge.id) } returns rentCharge
        every { leaseDataAccess.findById(lease.id) } returns lease
        every { dataAccess.findInFlightPaymentByChargeId(rentCharge.id) } returns null
        every { cardDataAccess.findById(card.id) } returns card.copy(tenantId = 99)

        assertThrows<ResourceNotFoundException> {
            module.payCharge(principal, rentCharge.id, card.id, null)
        }
        verify(exactly = 0) { stripeService.chargeCard(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `payCharge replays existing payment on same idempotency key`() {
        val existing = TestFixtures.payment(status = PaymentStatus.PROCESSING, idempotencyKey = "idem-1")
        every { dataAccess.findPaymentByIdempotencyKey("idem-1") } returns existing
        every { dataAccess.findChargeById(rentCharge.id) } returns rentCharge
        every { leaseDataAccess.findById(lease.id) } returns lease
        every { cardDataAccess.findById(card.id) } returns card

        val result = module.payCharge(principal, rentCharge.id, card.id, "idem-1")

        assertEquals(existing, result.payment)
        verify(exactly = 0) { dataAccess.savePayment(any()) }
        verify(exactly = 0) { stripeService.chargeCard(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `payCharge marks payment FAILED and throws 502 when Stripe is unreachable`() {
        val initiated = TestFixtures.payment(status = PaymentStatus.INITIATED)

        every { dataAccess.findChargeByIdForUpdate(rentCharge.id) } returns rentCharge
        every { leaseDataAccess.findById(lease.id) } returns lease
        every { dataAccess.findInFlightPaymentByChargeId(rentCharge.id) } returns null
        every { cardDataAccess.findById(card.id) } returns card
        every { tenantDataAccess.findById(1L) } returns tenant
        every { dataAccess.sumSucceededPayments(rentCharge.id) } returns BigDecimal.ZERO
        every { dataAccess.savePayment(any()) } returns initiated
        every { stripeService.chargeCard(any(), any(), any(), any(), any()) } throws ApiException("timeout", null, null, 0, null)
        every { dataAccess.updatePaymentStatus(initiated.id, PaymentStatus.FAILED, "Payment processor error") } returns
            initiated.copy(status = PaymentStatus.FAILED)

        assertThrows<UpstreamException> {
            module.payCharge(principal, rentCharge.id, card.id, null)
        }
        verify(exactly = 1) { dataAccess.updatePaymentStatus(initiated.id, PaymentStatus.FAILED, "Payment processor error") }
    }

    @Test
    fun `payCharge returns FAILED payment with decline reason when card is declined`() {
        val initiated = TestFixtures.payment(status = PaymentStatus.INITIATED)
        val failed = initiated.copy(status = PaymentStatus.FAILED, failureReason = "Card declined; code: card_declined")

        every { dataAccess.findChargeByIdForUpdate(rentCharge.id) } returns rentCharge
        every { leaseDataAccess.findById(lease.id) } returns lease
        every { dataAccess.findInFlightPaymentByChargeId(rentCharge.id) } returns null
        every { cardDataAccess.findById(card.id) } returns card
        every { tenantDataAccess.findById(1L) } returns tenant
        every { dataAccess.sumSucceededPayments(rentCharge.id) } returns BigDecimal.ZERO
        every { dataAccess.savePayment(any()) } returns initiated
        every { stripeService.chargeCard(any(), any(), any(), any(), any()) } throws
            CardException("Card declined", null, "card_declined", null, "generic_decline", null, 402, null)
        every { dataAccess.updatePaymentStatus(initiated.id, PaymentStatus.FAILED, "Card declined; code: card_declined", null) } returns failed

        val result = module.payCharge(principal, rentCharge.id, card.id, null)

        assertEquals(PaymentStatus.FAILED, result.payment.status)
        assertEquals("Card declined; code: card_declined", result.payment.failureReason)
        verify(exactly = 1) {
            dataAccess.updatePaymentStatus(initiated.id, PaymentStatus.FAILED, "Card declined; code: card_declined", null)
        }
        verify(exactly = 0) { dataAccess.saveCharge(any()) }
    }

    // --- applyStripeEvent ---

    @Test
    fun `applyStripeEvent transitions processing payment to succeeded and pays charge`() {
        val payment = TestFixtures.payment(status = PaymentStatus.PROCESSING)
        every { dataAccess.findPaymentByStripePaymentIntentId("pi_1") } returns payment
        every { dataAccess.updatePaymentStatus(payment.id, PaymentStatus.SUCCEEDED, null) } returns
            payment.copy(status = PaymentStatus.SUCCEEDED)
        every { dataAccess.findChargeById(payment.rentChargeId) } returns rentCharge
        every { dataAccess.saveCharge(any()) } answers { firstArg() }

        module.applyStripeEvent("pi_1", PaymentStatus.SUCCEEDED)

        verify(exactly = 1) { dataAccess.saveCharge(match { it.status == RentChargeStatus.PAID }) }
    }

    @Test
    fun `applyStripeEvent ignores event that would regress a succeeded payment`() {
        val payment = TestFixtures.payment(status = PaymentStatus.SUCCEEDED)
        every { dataAccess.findPaymentByStripePaymentIntentId("pi_1") } returns payment

        module.applyStripeEvent("pi_1", PaymentStatus.PROCESSING)

        verify(exactly = 0) { dataAccess.updatePaymentStatus(any(), any(), any(), any()) }
    }
}
