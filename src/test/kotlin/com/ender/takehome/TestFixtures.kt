package com.ender.takehome

import com.ender.takehome.model.*
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Test data builders for creating entities in tests.
 */
object TestFixtures {

    fun propertyManager(
        name: String = "Test PM",
        email: String = "pm@test.com",
    ) = PropertyManager(name = name, email = email)

    fun property(
        propertyManager: PropertyManager,
        name: String = "Test Property",
        address: String = "123 Test St",
    ) = Property(propertyManager = propertyManager, name = name, address = address)

    fun unit(
        property: Property,
        unitNumber: String = "101",
    ) = ApartmentUnit(property = property, unitNumber = unitNumber)

    fun tenant(
        firstName: String = "Test",
        lastName: String = "Tenant",
        email: String = "test@tenant.com",
        phone: String? = "555-0100",
    ) = Tenant(firstName = firstName, lastName = lastName, email = email, phone = phone)

    fun lease(
        tenant: Tenant,
        unit: ApartmentUnit,
        rentAmount: BigDecimal = BigDecimal("2000.00"),
        startDate: LocalDate = LocalDate.now().minusMonths(6),
        endDate: LocalDate = LocalDate.now().plusMonths(6),
        status: LeaseStatus = LeaseStatus.ACTIVE,
    ) = Lease(
        tenant = tenant,
        unit = unit,
        rentAmount = rentAmount,
        startDate = startDate,
        endDate = endDate,
        status = status,
    )

    fun rentCharge(
        lease: Lease,
        amount: BigDecimal = lease.rentAmount,
        dueDate: LocalDate = LocalDate.now().withDayOfMonth(1),
        status: RentChargeStatus = RentChargeStatus.PENDING,
    ) = RentCharge(
        lease = lease,
        amount = amount,
        dueDate = dueDate,
        status = status,
    )
}
