package com.ender.takehome.property

import com.ender.takehome.dto.request.CreatePropertyRequest
import com.ender.takehome.dto.request.CreateUnitRequest
import com.ender.takehome.dto.response.CursorPage
import com.ender.takehome.dto.response.PropertyResponse
import com.ender.takehome.dto.response.UnitResponse
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/properties")
@PreAuthorize("hasRole('PROPERTY_MANAGER')")
class PropertyApi(private val propertyModule: PropertyModule) {

    @GetMapping
    fun list(
        @RequestParam(required = false) startAfterId: Long?,
        @RequestParam(defaultValue = "20") limit: Int,
    ): CursorPage<PropertyResponse> {
        val page = propertyModule.getAllProperties(startAfterId, limit)
        return CursorPage(page.content.map { PropertyResponse.from(it) }, page.hasMore)
    }

    @GetMapping("/{id}")
    fun get(@PathVariable id: Long): PropertyResponse =
        PropertyResponse.from(propertyModule.getProperty(id))

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@Valid @RequestBody request: CreatePropertyRequest): PropertyResponse =
        PropertyResponse.from(propertyModule.createProperty(request.pmId, request.name, request.address))

    @GetMapping("/{id}/units")
    fun listUnits(
        @PathVariable id: Long,
        @RequestParam(required = false) startAfterId: Long?,
        @RequestParam(defaultValue = "20") limit: Int,
    ): CursorPage<UnitResponse> {
        val page = propertyModule.getUnits(id, startAfterId, limit)
        return CursorPage(page.content.map { UnitResponse.from(it) }, page.hasMore)
    }

    @PostMapping("/{id}/units")
    @ResponseStatus(HttpStatus.CREATED)
    fun createUnit(@PathVariable id: Long, @Valid @RequestBody request: CreateUnitRequest): UnitResponse =
        UnitResponse.from(propertyModule.createUnit(id, request.unitNumber))
}
