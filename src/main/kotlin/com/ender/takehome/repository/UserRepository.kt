package com.ender.takehome.repository

import com.ender.takehome.model.User
import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository

interface UserRepository : JpaRepository<User, Long> {

    @EntityGraph(attributePaths = ["tenant", "propertyManager"])
    fun findByEmail(email: String): User?
}
