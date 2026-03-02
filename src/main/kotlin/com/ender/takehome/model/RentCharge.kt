package com.ender.takehome.model

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

enum class RentChargeStatus { PENDING, PAID, OVERDUE }

data class RentCharge(
    val id: Long = 0,
    val leaseId: Long,
    val amount: BigDecimal,
    val dueDate: LocalDate,
    val status: RentChargeStatus = RentChargeStatus.PENDING,
    val createdAt: Instant = Instant.now(),
)
