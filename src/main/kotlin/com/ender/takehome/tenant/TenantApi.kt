package com.ender.takehome.tenant

import com.ender.takehome.config.UserPrincipal
import com.ender.takehome.dto.request.CreateTenantRequest
import com.ender.takehome.dto.request.UpdateTenantRequest
import com.ender.takehome.dto.response.CursorPage
import com.ender.takehome.dto.response.TenantResponse
import com.ender.takehome.exception.ResourceNotFoundException
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/tenants")
class TenantApi(private val tenantModule: TenantModule) {

    @GetMapping
    @PreAuthorize("hasRole('PROPERTY_MANAGER')")
    fun list(
        @RequestParam(required = false) startAfterId: Long?,
        @RequestParam(defaultValue = "20") limit: Int,
    ): CursorPage<TenantResponse> {
        val page = tenantModule.getAll(startAfterId, limit)
        return CursorPage(page.content.map { TenantResponse.from(it) }, page.hasMore)
    }

    @GetMapping("/{id}")
    fun get(@PathVariable id: Long): TenantResponse {
        val principal = UserPrincipal.current()
        if (principal.tenantId != null && principal.tenantId != id) {
            throw ResourceNotFoundException("Tenant not found: $id")
        }
        return TenantResponse.from(tenantModule.getById(id))
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('PROPERTY_MANAGER')")
    fun create(@Valid @RequestBody request: CreateTenantRequest): TenantResponse =
        TenantResponse.from(tenantModule.create(request.firstName, request.lastName, request.email, request.phone))

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('PROPERTY_MANAGER')")
    fun update(@PathVariable id: Long, @Valid @RequestBody request: UpdateTenantRequest): TenantResponse =
        TenantResponse.from(tenantModule.update(id, request.firstName, request.lastName, request.email, request.phone))
}
