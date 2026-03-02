package com.ender.takehome.repository

import com.ender.takehome.model.ManualPayment
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository

interface ManualPaymentRepository : JpaRepository<ManualPayment, Long> {

    @EntityGraph(attributePaths = ["rentCharge"])
    fun findByRentChargeId(rentChargeId: Long, pageable: Pageable): Page<ManualPayment>
}
