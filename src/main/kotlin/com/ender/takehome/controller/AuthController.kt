package com.ender.takehome.controller

import com.ender.takehome.config.JwtService
import com.ender.takehome.exception.ResourceNotFoundException
import com.ender.takehome.repository.UserRepository
import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import org.springframework.http.HttpStatus
import org.springframework.security.crypto.password.PasswordEncoder
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
    val role: String,
)

@RestController
@RequestMapping("/api/auth")
class AuthController(
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder,
    private val jwtService: JwtService,
) {

    @PostMapping("/login")
    fun login(@Valid @RequestBody request: LoginRequest): LoginResponse {
        val user = userRepository.findByEmail(request.email)
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials")

        if (!passwordEncoder.matches(request.password, user.passwordHash)) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials")
        }

        val token = jwtService.generateToken(
            userId = user.id,
            email = user.email,
            role = user.role.name,
            tenantId = user.tenant?.id,
            pmId = user.propertyManager?.id,
        )

        return LoginResponse(
            token = token,
            userId = user.id,
            email = user.email,
            role = user.role.name,
        )
    }
}
