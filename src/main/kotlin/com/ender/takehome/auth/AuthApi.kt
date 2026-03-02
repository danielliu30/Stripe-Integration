package com.ender.takehome.auth

import com.ender.takehome.config.JwtService
import com.ender.takehome.model.UserRole
import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException

data class LoginRequest(
    @field:NotBlank @field:Email val email: String,
    @field:NotBlank val password: String,
)

data class LoginResponse(
    val token: String,
    val userId: Long,
    val email: String,
    val role: UserRole,
)

@RestController
@RequestMapping("/api/auth")
class AuthApi(
    private val dataAccess: AuthDataAccess,
    private val passwordEncoder: org.springframework.security.crypto.password.PasswordEncoder,
    private val jwtService: JwtService,
) {

    @PostMapping("/login")
    fun login(@Valid @RequestBody request: LoginRequest): LoginResponse {
        val user = dataAccess.findByEmail(request.email)
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials")

        if (!passwordEncoder.matches(request.password, user.passwordHash)) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials")
        }

        val token = jwtService.generateToken(
            userId = user.id,
            email = user.email,
            role = user.role,
            tenantId = user.tenantId,
            pmId = user.pmId,
        )

        return LoginResponse(
            token = token,
            userId = user.id,
            email = user.email,
            role = user.role,
        )
    }
}
