package com.ender.takehome.controller

import com.ender.takehome.dto.request.CreateTenantRequest
import com.ender.takehome.dto.request.UpdateTenantRequest
import com.ender.takehome.dto.response.TenantResponse
import com.ender.takehome.exception.ResourceNotFoundException
import com.ender.takehome.repository.TenantRepository
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/tenants")
class TenantController(private val tenantRepository: TenantRepository) {

    @GetMapping
    fun list(): List<TenantResponse> = tenantRepository.findAll().map { TenantResponse.from(it) }

    @GetMapping("/{id}")
    fun get(@PathVariable id: Long): TenantResponse {
        val tenant = tenantRepository.findById(id)
            .orElseThrow { ResourceNotFoundException("Tenant not found: $id") }
        return TenantResponse.from(tenant)
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
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
