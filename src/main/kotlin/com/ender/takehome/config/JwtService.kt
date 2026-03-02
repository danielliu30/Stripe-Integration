package com.ender.takehome.config

import com.ender.takehome.model.UserRole
import io.jsonwebtoken.Claims
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.util.*
import javax.crypto.SecretKey

@Service
class JwtService(
    @Value("\${jwt.secret}") private val secret: String,
    @Value("\${jwt.expiration-ms}") private val expirationMs: Long,
) {

    private val key: SecretKey = Keys.hmacShaKeyFor(secret.toByteArray())

    fun generateToken(userId: Long, email: String, role: UserRole, tenantId: Long?, pmId: Long?): String {
        val now = Date()
        val builder = Jwts.builder()
            .subject(email)
            .claim("userId", userId)
            .claim("role", role.name)
            .issuedAt(now)
            .expiration(Date(now.time + expirationMs))
            .signWith(key)

        tenantId?.let { builder.claim("tenantId", it) }
        pmId?.let { builder.claim("pmId", it) }

        return builder.compact()
    }

    fun validateToken(token: String): Claims? {
        return try {
            Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .payload
        } catch (e: Exception) {
            null
        }
    }
}
