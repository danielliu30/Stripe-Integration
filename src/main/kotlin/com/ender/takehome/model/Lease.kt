package com.ender.takehome.model

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

enum class LeaseStatus { DRAFT, ACTIVE, EXPIRED }

data class Lease(
    val id: Long = 0,
    val tenantId: Long,
    val unitId: Long,
    val rentAmount: BigDecimal,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val status: LeaseStatus = LeaseStatus.ACTIVE,
    val createdAt: Instant = Instant.now(),
)
