package com.ender.takehome.model

import jakarta.persistence.*
import java.math.BigDecimal
import java.time.Instant

enum class PaymentMethod { CASH, CHECK, OTHER }

@Entity
@Table(name = "manual_payments")
class ManualPayment(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rent_charge_id", nullable = false)
    val rentCharge: RentCharge,

    @Column(nullable = false, precision = 10, scale = 2)
    val amount: BigDecimal,

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", nullable = false)
    val paymentMethod: PaymentMethod,

    @Column
    val notes: String? = null,

    @Column(name = "recorded_by", nullable = false)
    val recordedBy: String,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
)
