package com.ender.takehome.tenant

import com.ender.takehome.generated.tables.Tenants.TENANTS
import com.ender.takehome.generated.tables.records.TenantsRecord
import com.ender.takehome.model.Tenant
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Component
import java.time.ZoneOffset

@Component
class TenantDataAccess(private val dsl: DSLContext) {

    fun findById(id: Long): Tenant? =
        dsl.selectFrom(TENANTS)
            .where(TENANTS.ID.eq(id))
            .fetchOne()
            ?.toModel()

    fun findByStripeCustomerId(customerId: String): Tenant? =
        dsl.selectFrom(TENANTS)
            .where(TENANTS.STRIPE_CUSTOMER_ID.eq(customerId))
            .fetchOne()
            ?.toModel()

    fun findByEmail(email: String): Tenant? =
        dsl.selectFrom(TENANTS)
            .where(TENANTS.EMAIL.eq(email))
            .fetchOne()
            ?.toModel()

    fun findAllCursor(startAfterId: Long?, limit: Int): List<Tenant> =
        dsl.selectFrom(TENANTS)
            .where(cursorCondition(startAfterId))
            .orderBy(TENANTS.ID)
            .limit(limit)
            .fetch()
            .map { it.toModel() }

    fun save(tenant: Tenant): Tenant {
        if (tenant.id == 0L) {
            val record = dsl.newRecord(TENANTS).apply {
                firstName = tenant.firstName
                lastName = tenant.lastName
                email = tenant.email
                phone = tenant.phone
            }
            record.store()
            return tenant.copy(id = record.id!!)
        }
        dsl.update(TENANTS)
            .set(DSL.field(TENANTS.FIRST_NAME.unqualifiedName, TENANTS.FIRST_NAME.dataType), tenant.firstName)
            .set(DSL.field(TENANTS.LAST_NAME.unqualifiedName, TENANTS.LAST_NAME.dataType), tenant.lastName)
            .set(DSL.field(TENANTS.EMAIL.unqualifiedName, TENANTS.EMAIL.dataType), tenant.email)
            .set(DSL.field(TENANTS.PHONE.unqualifiedName, TENANTS.PHONE.dataType), tenant.phone)
            .set(DSL.field(TENANTS.STRIPE_CUSTOMER_ID.unqualifiedName, TENANTS.STRIPE_CUSTOMER_ID.dataType), tenant.stripeCustomerId)
            .where(TENANTS.ID.eq(tenant.id))
            .execute()
        return tenant
    }

    private fun cursorCondition(startAfterId: Long?) =
        if (startAfterId != null) TENANTS.ID.gt(startAfterId) else DSL.noCondition()

    private fun TenantsRecord.toModel() = Tenant(
        id = id!!,
        firstName = firstName!!,
        lastName = lastName!!,
        email = email!!,
        phone = phone,
        stripeCustomerId = stripeCustomerId,
        createdAt = createdAt!!.toInstant(ZoneOffset.UTC),
    )
}
