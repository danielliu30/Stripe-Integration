package com.ender.takehome.model

import java.time.Instant

data class Property(
    val id: Long = 0,
    val pmId: Long,
    val name: String,
    val address: String,
    val createdAt: Instant = Instant.now(),
)
