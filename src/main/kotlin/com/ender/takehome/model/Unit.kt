package com.ender.takehome.model

import java.time.Instant

data class PropertyUnit(
    val id: Long = 0,
    val propertyId: Long,
    val unitNumber: String,
    val createdAt: Instant = Instant.now(),
)
