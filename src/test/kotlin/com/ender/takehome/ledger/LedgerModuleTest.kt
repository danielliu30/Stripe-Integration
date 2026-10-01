package com.ender.takehome.ledger

import com.ender.takehome.TestFixtures
import com.ender.takehome.card.CardDataAccess
import com.ender.takehome.config.TransactionHelper
import com.ender.takehome.config.UserPrincipal
import com.ender.takehome.dto.request.RecordPaymentRequest
import com.ender.takehome.exception.ConflictException
import com.ender.takehome.exception.ResourceNotFoundException
import com.ender.takehome.leasing.LeaseDataAccess
import com.ender.takehome.model.Payment
import com.ender.takehome.model.Card
import com.ender.takehome.model.PaymentMethod
import com.ender.takehome.model.PaymentStatus
import com.ender.takehome.model.RentChargeStatus
import com.ender.takehome.model.UserRole
import com.ender.takehome.stripe.StripeChargeResult
import com.ender.takehome.stripe.StripePaymentService
import com.ender.takehome.tenant.TenantDataAccess
import io.mockk.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.jooq.exception.IntegrityConstraintViolationException
import java.math.BigDecimal
import java.time.LocalDate

class LedgerModuleTest {

    private val dataAccess = mockk<LedgerDataAccess>()
    private val cardDataAccess = mockk<CardDataAccess>()
    private val leaseDataAccess = mockk<LeaseDataAccess>()
    private val tenantDataAccess = mockk<TenantDataAccess>()
    private val stripeService = mockk<StripePaymentService>()
    private val transactionHelper = mockk<TransactionHelper>()
    private val module = LedgerModule(
        dataAccess,
        cardDataAccess,
        leaseDataAccess,
        tenantDataAccess,
        stripeService,
        transactionHelper,
    )

    private val lease = TestFixtures.lease()
    private val rentCharge = TestFixtures.rentCharge()
    private val card = Card(1L, 1L, "pm_test", "visa", "4242", 12, 2030)
    private val tenant = TestFixtures.tenant(id = 1L).copy(stripeCustomerId = "cus_test")
    private val principal = UserPrincipal(2L, tenant.email, UserRole.TENANT, tenantId = 1L, pmId = null)

    @BeforeEach
    fun setUp() {
        clearMocks(dataAccess, cardDataAccess, leaseDataAccess, tenantDataAccess, stripeService, transactionHelper)
        every { transactionHelper.executeWithRetry(any(), any(), any<() -> Any?>()) } answers {
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
    fun `getPayments returns only tenant payments`() {
        val payment = Payment(
            id = 1L,
            rentChargeId = 2L,
            amount = BigDecimal("2000.00"),
            paymentMethod = PaymentMethod.CHECK,
            recordedBy = "pm@test.com",
        )
        val principal = UserPrincipal(2L, "tenant@test.com", UserRole.TENANT, tenantId = 1L, pmId = null)
        every { dataAccess.findPaymentsByTenantIdCursor(1L, null, 21) } returns listOf(payment)

        val result = module.getPayments(null, 20, principal)

        assertEquals(listOf(payment), result.content)
        verify(exactly = 0) { dataAccess.findAllPaymentsCursor(any(), any()) }
    }

    @Test
    fun `getPayments returns all payments for property manager`() {
        val payment = Payment(
            id = 1L,
            rentChargeId = 2L,
            amount = BigDecimal("2000.00"),
            paymentMethod = PaymentMethod.CHECK,
            recordedBy = "pm@test.com",
        )
        val principal = UserPrincipal(1L, "pm@test.com", UserRole.PROPERTY_MANAGER, tenantId = null, pmId = 1L)
        every { dataAccess.findAllPaymentsCursor(null, 21) } returns listOf(payment)

        val result = module.getPayments(null, 20, principal)

        assertEquals(listOf(payment), result.content)
        verify(exactly = 0) { dataAccess.findPaymentsByTenantIdCursor(any(), any(), any()) }
    }

    @Test
    fun `payCharge hides another tenant rent charge`() {
        every { dataAccess.findChargeByIdForUpdate(rentCharge.id) } returns rentCharge
        every { leaseDataAccess.findById(lease.id) } returns lease.copy(tenantId = 2L)

        assertThrows<ResourceNotFoundException> {
            module.payCharge(principal, rentCharge.id, card.id, "test-key")
        }
        verify(exactly = 0) { stripeService.chargeCard(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `payCharge rejects an already paid rent charge`() {
        every { dataAccess.findChargeByIdForUpdate(rentCharge.id) } returns
            rentCharge.copy(status = RentChargeStatus.PAID)
        every { leaseDataAccess.findById(lease.id) } returns lease

        assertThrows<ConflictException> {
            module.payCharge(principal, rentCharge.id, card.id, "test-key")
        }
        verify(exactly = 0) { stripeService.chargeCard(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `payCharge returns existing payment before inserting or charging`() {
        val existing = Payment(
            id = 10L,
            rentChargeId = rentCharge.id,
            amount = rentCharge.amount,
            paymentMethod = PaymentMethod.CREDIT_CARD,
            status = PaymentStatus.SUCCEEDED,
            cardId = card.id,
            idempotencyKey = "replay-key",
            recordedBy = tenant.email,
        )
        every { dataAccess.findPaymentByIdempotencyKey("replay-key") } returns existing
        every { dataAccess.findChargeById(rentCharge.id) } returns rentCharge
        every { leaseDataAccess.findById(lease.id) } returns lease
        every { cardDataAccess.findById(card.id) } returns card

        val result = module.payCharge(principal, rentCharge.id, card.id, "replay-key")

        assertEquals(existing.id, result.payment.id)
        verify(exactly = 0) { dataAccess.savePayment(any()) }
        verify(exactly = 0) { stripeService.chargeCard(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `payCharge reloads winner after duplicate idempotency insert`() {
        val existing = Payment(
            id = 10L,
            rentChargeId = rentCharge.id,
            amount = rentCharge.amount,
            paymentMethod = PaymentMethod.CREDIT_CARD,
            status = PaymentStatus.INITIATED,
            cardId = card.id,
            idempotencyKey = "race-key",
            recordedBy = tenant.email,
        )
        every { dataAccess.findPaymentByIdempotencyKey("race-key") } returnsMany listOf(null, existing)
        stubPaymentPreparation()
        every { dataAccess.savePayment(any()) } throws IntegrityConstraintViolationException("duplicate")
        every { dataAccess.findChargeById(rentCharge.id) } returns rentCharge

        val result = module.payCharge(principal, rentCharge.id, card.id, "race-key")

        assertEquals(existing.id, result.payment.id)
        verify(exactly = 0) { stripeService.chargeCard(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `payCharge forwards client idempotency key and marks charge paid`() {
        stubPaymentPreparation()
        val initiated = Payment(
            id = 10L,
            rentChargeId = rentCharge.id,
            amount = rentCharge.amount,
            paymentMethod = PaymentMethod.CREDIT_CARD,
            status = PaymentStatus.INITIATED,
            cardId = card.id,
            recordedBy = tenant.email,
        )
        every { dataAccess.savePayment(any()) } returns initiated
        every {
            stripeService.chargeCard("cus_test", "pm_test", rentCharge.amount, "test-key", any())
        } returns StripeChargeResult("pi_test", PaymentStatus.SUCCEEDED, null)
        every {
            dataAccess.updatePaymentStatus(10L, PaymentStatus.SUCCEEDED, null, "pi_test")
        } returns initiated.copy(status = PaymentStatus.SUCCEEDED, stripePaymentIntentId = "pi_test")
        every { dataAccess.findChargeById(rentCharge.id) } returns rentCharge
        every { dataAccess.saveCharge(any()) } answers { firstArg() }

        val result = module.payCharge(principal, rentCharge.id, card.id, "test-key")

        assertEquals(PaymentStatus.SUCCEEDED, result.payment.status)
        verify(exactly = 1) {
            stripeService.chargeCard("cus_test", "pm_test", rentCharge.amount, "test-key", any())
        }
        verify { dataAccess.saveCharge(match { it.status == RentChargeStatus.PAID }) }
    }

    @Test
    fun `payment webhook advances processing payment and marks charge paid`() {
        val processing = Payment(
            id = 10L,
            rentChargeId = rentCharge.id,
            amount = rentCharge.amount,
            paymentMethod = PaymentMethod.CREDIT_CARD,
            status = PaymentStatus.PROCESSING,
            stripePaymentIntentId = "pi_test",
            recordedBy = tenant.email,
        )
        every { dataAccess.findPaymentByStripePaymentIntentIdForUpdate("pi_test") } returns processing
        every { dataAccess.updatePaymentStatus(10L, PaymentStatus.SUCCEEDED, null, "pi_test") } returns
            processing.copy(status = PaymentStatus.SUCCEEDED)
        every { dataAccess.findChargeById(rentCharge.id) } returns rentCharge
        every { dataAccess.saveCharge(any()) } answers { firstArg() }

        module.applyStripePaymentEvent("pi_test", PaymentStatus.SUCCEEDED)

        verify { dataAccess.saveCharge(match { it.status == RentChargeStatus.PAID }) }
    }

    @Test
    fun `payment webhook ignores events that regress a succeeded payment`() {
        val succeeded = Payment(
            id = 10L,
            rentChargeId = rentCharge.id,
            amount = rentCharge.amount,
            paymentMethod = PaymentMethod.CREDIT_CARD,
            status = PaymentStatus.SUCCEEDED,
            stripePaymentIntentId = "pi_test",
            recordedBy = tenant.email,
        )
        every { dataAccess.findPaymentByStripePaymentIntentIdForUpdate("pi_test") } returns succeeded

        module.applyStripePaymentEvent("pi_test", PaymentStatus.FAILED, "Late failure")

        verify(exactly = 0) { dataAccess.updatePaymentStatus(any(), any(), any(), any()) }
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

    private fun stubPaymentPreparation() {
        every { dataAccess.findChargeByIdForUpdate(rentCharge.id) } returns rentCharge
        every { leaseDataAccess.findById(lease.id) } returns lease
        every { dataAccess.findInFlightPaymentByChargeId(rentCharge.id) } returns null
        every { cardDataAccess.findById(card.id) } returns card
        every { tenantDataAccess.findById(tenant.id) } returns tenant
        every { dataAccess.sumSucceededPayments(rentCharge.id) } returns BigDecimal.ZERO
    }
}
