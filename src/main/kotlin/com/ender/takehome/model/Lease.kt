package com.ender.takehome.model

import jakarta.persistence.*
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

enum class LeaseStatus { DRAFT, ACTIVE, EXPIRED }

@Entity
@Table(name = "leases")
class Lease(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    val tenant: Tenant,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "unit_id", nullable = false)
    val unit: ApartmentUnit,

    @Column(name = "rent_amount", nullable = false, precision = 10, scale = 2)
    var rentAmount: BigDecimal,

    @Column(name = "start_date", nullable = false)
    val startDate: LocalDate,

    @Column(name = "end_date", nullable = false)
    val endDate: LocalDate,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: LeaseStatus = LeaseStatus.ACTIVE,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
)
