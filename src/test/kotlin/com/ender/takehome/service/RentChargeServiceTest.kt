package com.ender.takehome.service

import com.ender.takehome.TestFixtures
import com.ender.takehome.model.RentChargeStatus
import com.ender.takehome.repository.RentChargeRepository
import io.mockk.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDate

class RentChargeServiceTest {

    private val rentChargeRepository = mockk<RentChargeRepository>()
    private val service = RentChargeService(rentChargeRepository)

    private val lease = TestFixtures.lease()

    @BeforeEach
    fun setUp() {
        clearMocks(rentChargeRepository)
    }

    @Test
    fun `generateCharge creates new charge when none exists for that month`() {
        val dueDate = LocalDate.of(2025, 7, 1)
        every { rentChargeRepository.findByLeaseIdAndDueDate(lease.id, dueDate) } returns null
        every { rentChargeRepository.save(any()) } answers { firstArg() }

        val result = service.generateCharge(lease, dueDate)

        assertNotNull(result)
        assertEquals(lease.rentAmount, result!!.amount)
        assertEquals(dueDate, result.dueDate)
        verify(exactly = 1) { rentChargeRepository.save(any()) }
    }

    @Test
    fun `generateCharge returns null when charge already exists for that month`() {
        val dueDate = LocalDate.of(2025, 7, 1)
        val existing = TestFixtures.rentCharge(leaseId = lease.id, dueDate = dueDate)
        every { rentChargeRepository.findByLeaseIdAndDueDate(lease.id, dueDate) } returns existing

        val result = service.generateCharge(lease, dueDate)

        assertNull(result)
        verify(exactly = 0) { rentChargeRepository.save(any()) }
    }

    @Test
    fun `markPaid updates charge status to PAID`() {
        val charge = TestFixtures.rentCharge(leaseId = lease.id)
        every { rentChargeRepository.findById(charge.id) } returns charge
        every { rentChargeRepository.save(any()) } answers { firstArg() }

        val result = service.markPaid(charge.id)

        assertEquals(RentChargeStatus.PAID, result.status)
        verify { rentChargeRepository.save(match { it.status == RentChargeStatus.PAID }) }
    }
}
