package com.ender.takehome.controller

import com.ender.takehome.config.UserPrincipal
import com.ender.takehome.dto.request.CreateTenantRequest
import com.ender.takehome.dto.request.UpdateTenantRequest
import com.ender.takehome.dto.response.TenantResponse
import com.ender.takehome.exception.ResourceNotFoundException
import com.ender.takehome.repository.TenantRepository
import jakarta.validation.Valid
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/tenants")
class TenantController(private val tenantRepository: TenantRepository) {

    @GetMapping
    @PreAuthorize("hasRole('PROPERTY_MANAGER')")
    fun list(pageable: Pageable): Page<TenantResponse> =
        tenantRepository.findAll(pageable).map { TenantResponse.from(it) }

    @GetMapping("/{id}")
    fun get(@PathVariable id: Long): TenantResponse {
        val principal = UserPrincipal.current()
        val tenant = tenantRepository.findById(id)
            .orElseThrow { ResourceNotFoundException("Tenant not found: $id") }

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
            .orElseThrow { ResourceNotFoundException("Tenant not found: $id") }

        request.firstName?.let { tenant.firstName = it }
        request.lastName?.let { tenant.lastName = it }
        request.email?.let { tenant.email = it }
        request.phone?.let { tenant.phone = it }

        return TenantResponse.from(tenantRepository.save(tenant))
    }
}
