package com.ender.takehome.repository

import com.ender.takehome.model.ManualPayment
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface ManualPaymentRepository : JpaRepository<ManualPayment, Long> {

    @EntityGraph(attributePaths = ["rentCharge"])
    @Query("SELECT mp FROM ManualPayment mp WHERE mp.rentCharge.id = :rentChargeId AND (:startAfterId IS NULL OR mp.id > :startAfterId) ORDER BY mp.id")
    fun findByRentChargeIdCursor(rentChargeId: Long, startAfterId: Long?, pageable: Pageable): List<ManualPayment>
}
