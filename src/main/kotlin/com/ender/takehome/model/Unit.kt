package com.ender.takehome.model

import java.time.Instant

data class ApartmentUnit(
    val id: Long = 0,
    val propertyId: Long,
    val unitNumber: String,
    val createdAt: Instant = Instant.now(),
)
