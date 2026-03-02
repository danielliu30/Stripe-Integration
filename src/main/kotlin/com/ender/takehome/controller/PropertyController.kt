package com.ender.takehome.controller

import com.ender.takehome.dto.request.CreatePropertyRequest
import com.ender.takehome.dto.request.CreateUnitRequest
import com.ender.takehome.dto.response.CursorPage
import com.ender.takehome.dto.response.PropertyResponse
import com.ender.takehome.dto.response.UnitResponse
import com.ender.takehome.exception.ResourceNotFoundException
import com.ender.takehome.model.ApartmentUnit
import com.ender.takehome.model.Property
import com.ender.takehome.repository.PropertyManagerRepository
import com.ender.takehome.repository.PropertyRepository
import com.ender.takehome.repository.UnitRepository
import jakarta.validation.Valid
import org.springframework.data.domain.PageRequest
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/properties")
@PreAuthorize("hasRole('PROPERTY_MANAGER')")
class PropertyController(
    private val propertyRepository: PropertyRepository,
    private val propertyManagerRepository: PropertyManagerRepository,
    private val unitRepository: UnitRepository,
) {

    @GetMapping
    fun list(
        @RequestParam(required = false) startAfterId: Long?,
        @RequestParam(defaultValue = "20") limit: Int,
    ): CursorPage<PropertyResponse> {
        val sanitized = CursorPage.sanitizeLimit(limit)
        val items = propertyRepository.findAllCursor(startAfterId, PageRequest.ofSize(sanitized + 1))
        return CursorPage.of(items, sanitized).let {
            CursorPage(it.content.map { p -> PropertyResponse.from(p) }, it.hasMore)
        }
    }

    @GetMapping("/{id}")
    fun get(@PathVariable id: Long): PropertyResponse {
        val property = propertyRepository.findById(id)
            .orElseThrow { ResourceNotFoundException("Property not found: $id") }
        return PropertyResponse.from(property)
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@Valid @RequestBody request: CreatePropertyRequest): PropertyResponse {
        val pm = propertyManagerRepository.findById(request.pmId)
            .orElseThrow { ResourceNotFoundException("Property manager not found: ${request.pmId}") }

        val property = Property(propertyManager = pm, name = request.name, address = request.address)
        return PropertyResponse.from(propertyRepository.save(property))
    }

    @GetMapping("/{id}/units")
    fun listUnits(
        @PathVariable id: Long,
        @RequestParam(required = false) startAfterId: Long?,
        @RequestParam(defaultValue = "20") limit: Int,
    ): CursorPage<UnitResponse> {
        propertyRepository.findById(id)
            .orElseThrow { ResourceNotFoundException("Property not found: $id") }

        val sanitized = CursorPage.sanitizeLimit(limit)
        val items = unitRepository.findByPropertyIdCursor(id, startAfterId, PageRequest.ofSize(sanitized + 1))
        return CursorPage.of(items, sanitized).let {
            CursorPage(it.content.map { u -> UnitResponse.from(u) }, it.hasMore)
        }
    }

    @PostMapping("/{id}/units")
    @ResponseStatus(HttpStatus.CREATED)
    fun createUnit(@PathVariable id: Long, @Valid @RequestBody request: CreateUnitRequest): UnitResponse {
        val property = propertyRepository.findById(id)
            .orElseThrow { ResourceNotFoundException("Property not found: $id") }

        val unit = ApartmentUnit(property = property, unitNumber = request.unitNumber)
        return UnitResponse.from(unitRepository.save(unit))
    }
}
