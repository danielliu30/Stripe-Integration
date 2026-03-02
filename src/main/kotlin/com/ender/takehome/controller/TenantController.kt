package com.ender.takehome.controller

import com.ender.takehome.config.UserPrincipal
import com.ender.takehome.dto.request.CreateTenantRequest
import com.ender.takehome.dto.request.UpdateTenantRequest
import com.ender.takehome.dto.response.CursorPage
import com.ender.takehome.dto.response.TenantResponse
import com.ender.takehome.exception.ResourceNotFoundException
import com.ender.takehome.repository.TenantRepository
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/tenants")
class TenantController(private val tenantRepository: TenantRepository) {

    @GetMapping
    @PreAuthorize("hasRole('PROPERTY_MANAGER')")
    fun list(
        @RequestParam(required = false) startAfterId: Long?,
        @RequestParam(defaultValue = "20") limit: Int,
    ): CursorPage<TenantResponse> {
        val sanitized = CursorPage.sanitizeLimit(limit)
        val items = tenantRepository.findAllCursor(startAfterId, sanitized + 1)
        return CursorPage.of(items, sanitized).let {
            CursorPage(it.content.map { t -> TenantResponse.from(t) }, it.hasMore)
        }
    }

    @GetMapping("/{id}")
    fun get(@PathVariable id: Long): TenantResponse {
        val principal = UserPrincipal.current()
        val tenant = tenantRepository.findById(id)
            ?: throw ResourceNotFoundException("Tenant not found: $id")

        if (principal.tenantId != null && principal.tenantId != id) {
            throw ResourceNotFoundException("Tenant not found: $id")
        }

        return TenantResponse.from(tenant)
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('PROPERTY_MANAGER')")
    fun create(@Valid @RequestBody request: CreateTenantRequest): TenantResponse {
        val tenant = com.ender.takehome.model.Tenant(
            firstName = request.firstName,
            lastName = request.lastName,
            email = request.email,
            phone = request.phone,
        )
        return TenantResponse.from(tenantRepository.save(tenant))
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('PROPERTY_MANAGER')")
    fun update(@PathVariable id: Long, @Valid @RequestBody request: UpdateTenantRequest): TenantResponse {
        val tenant = tenantRepository.findById(id)
            ?: throw ResourceNotFoundException("Tenant not found: $id")

        val updated = tenant.copy(
            firstName = request.firstName ?: tenant.firstName,
            lastName = request.lastName ?: tenant.lastName,
            email = request.email ?: tenant.email,
            phone = request.phone ?: tenant.phone,
        )

        return TenantResponse.from(tenantRepository.save(updated))
    }
}
