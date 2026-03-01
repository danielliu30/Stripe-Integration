package com.ender.takehome.model

import jakarta.persistence.*
import java.time.Instant

@Entity
@Table(name = "property_managers")
class PropertyManager(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(nullable = false)
    var name: String,

    @Column(nullable = false, unique = true)
    var email: String,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
)
