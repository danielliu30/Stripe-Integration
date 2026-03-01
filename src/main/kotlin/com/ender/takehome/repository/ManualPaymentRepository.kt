package com.ender.takehome.repository

import com.ender.takehome.model.ManualPayment
import org.springframework.data.jpa.repository.JpaRepository

interface ManualPaymentRepository : JpaRepository<ManualPayment, Long> {
    fun findByRentChargeId(rentChargeId: Long): List<ManualPayment>
}
