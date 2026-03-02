package com.ender.takehome.worker

import com.ender.takehome.leasing.LeaseModule
import com.ender.takehome.ledger.LedgerModule
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.LocalDate

data class RentChargeGenerationParams(
    val dueDate: LocalDate,
)

@Component
class RentChargeGenerationJob(
    private val leaseModule: LeaseModule,
    private val ledgerModule: LedgerModule,
) : BackgroundJob<RentChargeGenerationParams> {

    private val log = LoggerFactory.getLogger(RentChargeGenerationJob::class.java)

    override val type = BackgroundJobType.GENERATE_RENT_CHARGES

    override fun deserialize(params: Map<String, Any>): RentChargeGenerationParams {
        val dueDate = (params["dueDate"] as? String)
            ?.let { LocalDate.parse(it) }
            ?: LocalDate.now().withDayOfMonth(1)
        return RentChargeGenerationParams(dueDate = dueDate)
    }

    override fun process(params: RentChargeGenerationParams) {
        val activeLeases = leaseModule.getActiveLeases()
        log.info("Generating rent charges for ${activeLeases.size} active leases, due date: ${params.dueDate}")

        var created = 0
        for (lease in activeLeases) {
            val charge = ledgerModule.generateCharge(lease, params.dueDate)
            if (charge != null) created++
        }

        log.info("Created $created new rent charges (${activeLeases.size - created} already existed)")
    }
}
