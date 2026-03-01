package com.ender.takehome.controller

import com.ender.takehome.dto.response.RentChargeResponse
import com.ender.takehome.service.RentChargeService
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/rent-charges")
class RentChargeController(private val rentChargeService: RentChargeService) {

    @GetMapping("/{id}")
    fun get(@PathVariable id: Long): RentChargeResponse =
        RentChargeResponse.from(rentChargeService.getById(id))

    @GetMapping(params = ["leaseId"])
    fun getByLease(@RequestParam leaseId: Long): List<RentChargeResponse> =
        rentChargeService.getByLeaseId(leaseId).map { RentChargeResponse.from(it) }

    @GetMapping(params = ["leaseId", "status"])
    fun getPendingByLease(
        @RequestParam leaseId: Long,
        @RequestParam status: String,
    ): List<RentChargeResponse> {
        if (status.uppercase() != "PENDING") {
            return rentChargeService.getByLeaseId(leaseId).map { RentChargeResponse.from(it) }
        }
        return rentChargeService.getPendingByLeaseId(leaseId).map { RentChargeResponse.from(it) }
    }
}
