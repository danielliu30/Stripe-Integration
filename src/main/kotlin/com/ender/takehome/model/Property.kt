package com.ender.takehome.model

import jakarta.persistence.*
import java.time.Instant

@Entity
@Table(name = "properties")
class Property(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pm_id", nullable = false)
    val propertyManager: PropertyManager,

    @Column(nullable = false)
    var name: String,

    @Column(nullable = false)
    var address: String,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
)
