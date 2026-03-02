package com.ender.takehome.config

import com.ender.takehome.model.UserRole
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

@Component
class JwtAuthenticationFilter(
    private val jwtService: JwtService,
) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val header = request.getHeader("Authorization")
        if (header != null && header.startsWith("Bearer ")) {
            val token = header.substring(7)
            val claims = jwtService.validateToken(token)

            if (claims != null) {
                val principal = UserPrincipal(
                    userId = (claims["userId"] as Number).toLong(),
                    email = claims.subject,
                    role = UserRole.valueOf(claims["role"] as String),
                    tenantId = (claims["tenantId"] as? Number)?.toLong(),
                    pmId = (claims["pmId"] as? Number)?.toLong(),
                )
                val auth = UsernamePasswordAuthenticationToken(principal, null, principal.authorities)
                SecurityContextHolder.getContext().authentication = auth
            }
        }

        filterChain.doFilter(request, response)
    }
}
