package com.ender.takehome.model

import java.time.Instant

enum class UserRole { TENANT, PROPERTY_MANAGER }

data class User(
    val id: Long = 0,
    val email: String,
    val passwordHash: String,
    val role: UserRole,
    val tenantId: Long? = null,
    val pmId: Long? = null,
    val createdAt: Instant = Instant.now(),
)
