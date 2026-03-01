package com.ender.takehome.model

import jakarta.persistence.*
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

enum class RentChargeStatus { PENDING, PAID, OVERDUE }

@Entity
@Table(name = "rent_charges")
class RentCharge(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lease_id", nullable = false)
    val lease: Lease,

    @Column(nullable = false, precision = 10, scale = 2)
    val amount: BigDecimal,

    @Column(name = "due_date", nullable = false)
    val dueDate: LocalDate,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: RentChargeStatus = RentChargeStatus.PENDING,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
)
