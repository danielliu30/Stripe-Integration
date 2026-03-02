package com.ender.takehome.ledger

import com.ender.takehome.TestFixtures
import com.ender.takehome.dto.request.RecordPaymentRequest
import com.ender.takehome.model.PaymentMethod
import com.ender.takehome.model.RentChargeStatus
import io.mockk.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class LedgerModuleTest {

    private val dataAccess = mockk<LedgerDataAccess>()
    private val module = LedgerModule(dataAccess)

    private val lease = TestFixtures.lease()
    private val rentCharge = TestFixtures.rentCharge()

    @BeforeEach
    fun setUp() {
        clearMocks(dataAccess)
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
}
