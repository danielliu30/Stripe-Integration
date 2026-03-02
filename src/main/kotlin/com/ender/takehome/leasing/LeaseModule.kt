package com.ender.takehome.leasing

import com.ender.takehome.dto.request.CreateLeaseRequest
import com.ender.takehome.dto.response.CursorPage
import com.ender.takehome.exception.ResourceNotFoundException
import com.ender.takehome.model.Lease
import com.ender.takehome.model.LeaseStatus
import com.ender.takehome.property.PropertyModule
import com.ender.takehome.tenant.TenantModule
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class LeaseModule(
    private val dataAccess: LeaseDataAccess,
    private val tenantModule: TenantModule,
    private val propertyModule: PropertyModule,
) {

    fun getAll(startAfterId: Long?, limit: Int): CursorPage<Lease> {
        val sanitized = CursorPage.sanitizeLimit(limit)
        val items = dataAccess.findAllCursor(startAfterId, sanitized + 1)
        return CursorPage.of(items, sanitized)
    }

    fun getById(id: Long): Lease =
        dataAccess.findById(id) ?: throw ResourceNotFoundException("Lease not found: $id")

    fun getByTenantId(tenantId: Long, startAfterId: Long?, limit: Int): CursorPage<Lease> {
        val sanitized = CursorPage.sanitizeLimit(limit)
        val items = dataAccess.findByTenantIdCursor(tenantId, startAfterId, sanitized + 1)
        return CursorPage.of(items, sanitized)
    }

    fun getActiveLeases(): List<Lease> = dataAccess.findByStatus(LeaseStatus.ACTIVE)

    @Transactional
    fun create(request: CreateLeaseRequest): Lease {
        tenantModule.getById(request.tenantId)
        propertyModule.getUnit(request.unitId)

        require(request.endDate.isAfter(request.startDate)) { "End date must be after start date" }

        val lease = Lease(
            tenantId = request.tenantId,
            unitId = request.unitId,
            rentAmount = request.rentAmount,
            startDate = request.startDate,
            endDate = request.endDate,
        )
        return dataAccess.save(lease)
    }
}
