package com.ender.takehome.model

import jakarta.persistence.*
import java.time.Instant

enum class UserRole { TENANT, PROPERTY_MANAGER }

@Entity
@Table(name = "users")
class User(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(nullable = false, unique = true)
    val email: String,

    @Column(name = "password_hash", nullable = false)
    val passwordHash: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val role: UserRole,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id")
    val tenant: Tenant? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pm_id")
    val propertyManager: PropertyManager? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
)
