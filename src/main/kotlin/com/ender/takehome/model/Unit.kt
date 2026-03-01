package com.ender.takehome.model

import jakarta.persistence.*
import java.time.Instant

@Entity
@Table(name = "units")
class ApartmentUnit(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "property_id", nullable = false)
    val property: Property,

    @Column(name = "unit_number", nullable = false)
    var unitNumber: String,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
)
