package com.ender.takehome.model

import java.time.Instant

data class PropertyManager(
    val id: Long = 0,
    val name: String,
    val email: String,
    val createdAt: Instant = Instant.now(),
)
