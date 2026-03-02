package com.ender.takehome.controller

import com.ender.takehome.config.UserPrincipal
import com.ender.takehome.dto.request.CreateLeaseRequest
import com.ender.takehome.dto.response.LeaseResponse
import com.ender.takehome.model.UserRole
import com.ender.takehome.service.LeaseService
import jakarta.validation.Valid
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/leases")
class LeaseController(private val leaseService: LeaseService) {

    @GetMapping
    fun list(pageable: Pageable): Page<LeaseResponse> {
        val principal = UserPrincipal.current()
        return if (principal.role == UserRole.TENANT && principal.tenantId != null) {
            leaseService.getByTenantId(principal.tenantId, pageable)
        } else {
            leaseService.getAll(pageable)
        }.map { LeaseResponse.from(it) }
    }

    @GetMapping("/{id}")
    fun get(@PathVariable id: Long): LeaseResponse = LeaseResponse.from(leaseService.getById(id))

    @GetMapping(params = ["tenantId"])
    @PreAuthorize("hasRole('PROPERTY_MANAGER')")
    fun getByTenant(@RequestParam tenantId: Long, pageable: Pageable): Page<LeaseResponse> =
        leaseService.getByTenantId(tenantId, pageable).map { LeaseResponse.from(it) }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('PROPERTY_MANAGER')")
    fun create(@Valid @RequestBody request: CreateLeaseRequest): LeaseResponse =
        LeaseResponse.from(leaseService.create(request))
}
