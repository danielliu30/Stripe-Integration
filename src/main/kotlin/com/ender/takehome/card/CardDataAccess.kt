package com.ender.takehome.card

import com.ender.takehome.generated.tables.Cards.CARDS
import com.ender.takehome.generated.tables.records.CardsRecord
import com.ender.takehome.model.Card
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Component
import java.time.ZoneOffset

@Component
class CardDataAccess(private val dsl: DSLContext) {

    fun findById(id: Long): Card? =
        dsl.selectFrom(CARDS)
            .where(CARDS.ID.eq(id))
            .fetchOne()
            ?.toModel()

    fun findByStripePaymentMethodId(paymentMethodId: String): Card? =
        dsl.selectFrom(CARDS)
            .where(CARDS.STRIPE_PAYMENT_METHOD_ID.eq(paymentMethodId))
            .fetchOne()
            ?.toModel()

    fun findByIds(ids: Collection<Long>): List<Card> =
        if (ids.isEmpty()) emptyList()
        else dsl.selectFrom(CARDS)
            .where(CARDS.ID.`in`(ids))
            .fetch()
            .map { it.toModel() }

    fun findByTenantIdCursor(tenantId: Long, startAfterId: Long?, limit: Int): List<Card> =
        dsl.selectFrom(CARDS)
            .where(CARDS.TENANT_ID.eq(tenantId))
            .and(if (startAfterId != null) CARDS.ID.gt(startAfterId) else DSL.noCondition())
            .orderBy(CARDS.ID)
            .limit(limit)
            .fetch()
            .map { it.toModel() }

    fun delete(id: Long) {
        dsl.deleteFrom(CARDS)
            .where(CARDS.ID.eq(id))
            .execute()
    }

    fun save(card: Card): Card {
        val record = dsl.newRecord(CARDS).apply {
            tenantId = card.tenantId
            stripePaymentMethodId = card.stripePaymentMethodId
            brand = card.brand
            last4 = card.last4
            expMonth = card.expMonth
            expYear = card.expYear
        }
        record.store()
        val id = requireNotNull(record.id)
        return requireNotNull(findById(id))
    }

    private fun CardsRecord.toModel() = Card(
        id = id!!,
        tenantId = tenantId!!,
        stripePaymentMethodId = stripePaymentMethodId!!,
        brand = brand!!,
        last4 = last4!!,
        expMonth = expMonth!!,
        expYear = expYear!!,
        createdAt = createdAt!!.toInstant(ZoneOffset.UTC),
    )
}
