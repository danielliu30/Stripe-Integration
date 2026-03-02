package com.ender.takehome.repository

import com.ender.takehome.generated.tables.Users.USERS
import com.ender.takehome.generated.tables.records.UsersRecord
import com.ender.takehome.model.User
import com.ender.takehome.model.UserRole
import org.jooq.DSLContext
import org.springframework.stereotype.Component
import java.time.ZoneOffset

@Component
class UserRepository(private val dsl: DSLContext) {

    fun findByEmail(email: String): User? =
        dsl.selectFrom(USERS)
            .where(USERS.EMAIL.eq(email))
            .fetchOne()
            ?.toModel()

    private fun UsersRecord.toModel() = User(
        id = id!!,
        email = email!!,
        passwordHash = passwordHash!!,
        role = UserRole.valueOf(role!!),
        tenantId = tenantId,
        pmId = pmId,
        createdAt = createdAt!!.toInstant(ZoneOffset.UTC),
    )
}
