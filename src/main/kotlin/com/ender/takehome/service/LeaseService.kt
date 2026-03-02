package com.ender.takehome.service

import com.ender.takehome.dto.request.CreateLeaseRequest
import com.ender.takehome.dto.response.CursorPage
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

    fun getAll(startAfterId: Long?, limit: Int): CursorPage<Lease> {
        val sanitized = CursorPage.sanitizeLimit(limit)
        val items = leaseRepository.findAllCursor(startAfterId, sanitized + 1)
        return CursorPage.of(items, sanitized)
    }

    fun getById(id: Long): Lease =
        leaseRepository.findById(id) ?: throw ResourceNotFoundException("Lease not found: $id")

    fun getByTenantId(tenantId: Long, startAfterId: Long?, limit: Int): CursorPage<Lease> {
        val sanitized = CursorPage.sanitizeLimit(limit)
        val items = leaseRepository.findByTenantIdCursor(tenantId, startAfterId, sanitized + 1)
        return CursorPage.of(items, sanitized)
    }

    fun getActiveLeases(): List<Lease> = leaseRepository.findByStatus(LeaseStatus.ACTIVE)

    @Transactional
    fun create(request: CreateLeaseRequest): Lease {
        tenantRepository.findById(request.tenantId)
            ?: throw ResourceNotFoundException("Tenant not found: ${request.tenantId}")
        unitRepository.findById(request.unitId)
            ?: throw ResourceNotFoundException("Unit not found: ${request.unitId}")

        require(request.endDate.isAfter(request.startDate)) { "End date must be after start date" }

        val lease = Lease(
            tenantId = request.tenantId,
            unitId = request.unitId,
            rentAmount = request.rentAmount,
            startDate = request.startDate,
            endDate = request.endDate,
        )
        return leaseRepository.save(lease)
    }
}
