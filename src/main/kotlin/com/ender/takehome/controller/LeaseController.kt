package com.ender.takehome.controller

import com.ender.takehome.dto.request.CreateLeaseRequest
import com.ender.takehome.dto.response.LeaseResponse
import com.ender.takehome.service.LeaseService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/leases")
class LeaseController(private val leaseService: LeaseService) {

    @GetMapping
    fun list(): List<LeaseResponse> = leaseService.getAll().map { LeaseResponse.from(it) }

    @GetMapping("/{id}")
    fun get(@PathVariable id: Long): LeaseResponse = LeaseResponse.from(leaseService.getById(id))

    @GetMapping(params = ["tenantId"])
    fun getByTenant(@RequestParam tenantId: Long): List<LeaseResponse> =
        leaseService.getByTenantId(tenantId).map { LeaseResponse.from(it) }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@Valid @RequestBody request: CreateLeaseRequest): LeaseResponse =
        LeaseResponse.from(leaseService.create(request))
}
