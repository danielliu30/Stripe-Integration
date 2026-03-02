package com.ender.takehome.controller

import com.ender.takehome.dto.request.CreatePropertyRequest
import com.ender.takehome.dto.request.CreateUnitRequest
import com.ender.takehome.dto.response.PropertyResponse
import com.ender.takehome.dto.response.UnitResponse
import com.ender.takehome.exception.ResourceNotFoundException
import com.ender.takehome.model.ApartmentUnit
import com.ender.takehome.model.Property
import com.ender.takehome.repository.PropertyManagerRepository
import com.ender.takehome.repository.PropertyRepository
import com.ender.takehome.repository.UnitRepository
import jakarta.validation.Valid
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
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
    fun list(pageable: Pageable): Page<PropertyResponse> =
        propertyRepository.findAll(pageable).map { PropertyResponse.from(it) }

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
    fun listUnits(@PathVariable id: Long, pageable: Pageable): Page<UnitResponse> {
        propertyRepository.findById(id)
            .orElseThrow { ResourceNotFoundException("Property not found: $id") }
        return unitRepository.findByPropertyId(id, pageable).map { UnitResponse.from(it) }
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
