package com.ender.takehome.service

import com.ender.takehome.TestFixtures
import com.ender.takehome.dto.request.RecordManualPaymentRequest
import com.ender.takehome.model.PaymentMethod
import com.ender.takehome.repository.ManualPaymentRepository
import io.mockk.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class ManualPaymentServiceTest {

    private val manualPaymentRepository = mockk<ManualPaymentRepository>()
    private val rentChargeService = mockk<RentChargeService>()
    private val service = ManualPaymentService(manualPaymentRepository, rentChargeService)

    private val rentCharge = TestFixtures.rentCharge()

    @BeforeEach
    fun setUp() {
        clearMocks(manualPaymentRepository, rentChargeService)
    }

    @Test
    fun `recordPayment creates payment and marks charge as paid`() {
        val request = RecordManualPaymentRequest(
            rentChargeId = rentCharge.id,
            amount = BigDecimal("2000.00"),
            paymentMethod = PaymentMethod.CHECK,
            notes = "Check #1234",
            recordedBy = "admin@test.com",
        )

        every { rentChargeService.getById(rentCharge.id) } returns rentCharge
        every { manualPaymentRepository.save(any()) } answers { firstArg() }
        every { rentChargeService.markPaid(rentCharge.id) } returns rentCharge

        val result = service.recordPayment(request)

        assertEquals(BigDecimal("2000.00"), result.amount)
        assertEquals(PaymentMethod.CHECK, result.paymentMethod)
        assertEquals("Check #1234", result.notes)
        verify(exactly = 1) { rentChargeService.markPaid(rentCharge.id) }
    }
}
