package com.ender.takehome.controller

import com.ender.takehome.config.UserPrincipal
import com.ender.takehome.dto.request.CreateLeaseRequest
import com.ender.takehome.dto.response.CursorPage
import com.ender.takehome.dto.response.LeaseResponse
import com.ender.takehome.model.UserRole
import com.ender.takehome.service.LeaseService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/leases")
class LeaseController(private val leaseService: LeaseService) {

    @GetMapping
    fun list(
        @RequestParam(required = false) startAfterId: Long?,
        @RequestParam(defaultValue = "20") limit: Int,
    ): CursorPage<LeaseResponse> {
        val principal = UserPrincipal.current()
        val page = if (principal.role == UserRole.TENANT && principal.tenantId != null) {
            leaseService.getByTenantId(principal.tenantId, startAfterId, limit)
        } else {
            leaseService.getAll(startAfterId, limit)
        }
        return CursorPage(page.content.map { LeaseResponse.from(it) }, page.hasMore)
    }

    @GetMapping("/{id}")
    fun get(@PathVariable id: Long): LeaseResponse = LeaseResponse.from(leaseService.getById(id))

    @GetMapping(params = ["tenantId"])
    @PreAuthorize("hasRole('PROPERTY_MANAGER')")
    fun getByTenant(
        @RequestParam tenantId: Long,
        @RequestParam(required = false) startAfterId: Long?,
        @RequestParam(defaultValue = "20") limit: Int,
    ): CursorPage<LeaseResponse> {
        val page = leaseService.getByTenantId(tenantId, startAfterId, limit)
        return CursorPage(page.content.map { LeaseResponse.from(it) }, page.hasMore)
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('PROPERTY_MANAGER')")
    fun create(@Valid @RequestBody request: CreateLeaseRequest): LeaseResponse =
        LeaseResponse.from(leaseService.create(request))
}
