package com.ender.takehome.worker

import com.ender.takehome.service.LeaseService
import com.ender.takehome.service.RentChargeService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.LocalDate

/**
 * Background job that generates rent charges for active leases.
 *
 * Expected payload:
 *   { "dueDate": "2025-07-01" }
 *
 * If no dueDate is provided, defaults to the 1st of the current month.
 */
@Component("GENERATE_RENT_CHARGES")
class RentChargeGenerationJob(
    private val leaseService: LeaseService,
    private val rentChargeService: RentChargeService,
) : JobHandler {

    private val log = LoggerFactory.getLogger(RentChargeGenerationJob::class.java)

    override fun handle(payload: Map<String, Any>) {
        val dueDate = (payload["dueDate"] as? String)
            ?.let { LocalDate.parse(it) }
            ?: LocalDate.now().withDayOfMonth(1)

        val activeLeases = leaseService.getActiveLeases()
        log.info("Generating rent charges for ${activeLeases.size} active leases, due date: $dueDate")

        var created = 0
        for (lease in activeLeases) {
            val charge = rentChargeService.generateCharge(lease, dueDate)
            if (charge != null) created++
        }

        log.info("Created $created new rent charges (${activeLeases.size - created} already existed)")
    }
}
