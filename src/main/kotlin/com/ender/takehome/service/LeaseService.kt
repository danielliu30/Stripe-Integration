package com.ender.takehome.service

import com.ender.takehome.dto.request.CreateLeaseRequest
import com.ender.takehome.exception.ResourceNotFoundException
import com.ender.takehome.model.Lease
import com.ender.takehome.model.LeaseStatus
import com.ender.takehome.repository.LeaseRepository
import com.ender.takehome.repository.TenantRepository
import com.ender.takehome.repository.UnitRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class LeaseService(
    private val leaseRepository: LeaseRepository,
    private val tenantRepository: TenantRepository,
    private val unitRepository: UnitRepository,
) {

    fun getAll(): List<Lease> = leaseRepository.findAll()

    fun getById(id: Long): Lease =
        leaseRepository.findById(id).orElseThrow { ResourceNotFoundException("Lease not found: $id") }

    fun getByTenantId(tenantId: Long): List<Lease> = leaseRepository.findByTenantId(tenantId)

    fun getActiveLeases(): List<Lease> = leaseRepository.findByStatus(LeaseStatus.ACTIVE)

    @Transactional
    fun create(request: CreateLeaseRequest): Lease {
        val tenant = tenantRepository.findById(request.tenantId)
            .orElseThrow { ResourceNotFoundException("Tenant not found: ${request.tenantId}") }
        val unit = unitRepository.findById(request.unitId)
            .orElseThrow { ResourceNotFoundException("Unit not found: ${request.unitId}") }

        require(request.endDate.isAfter(request.startDate)) { "End date must be after start date" }

        val lease = Lease(
            tenant = tenant,
            unit = unit,
            rentAmount = request.rentAmount,
            startDate = request.startDate,
            endDate = request.endDate,
        )
        return leaseRepository.save(lease)
    }
}
