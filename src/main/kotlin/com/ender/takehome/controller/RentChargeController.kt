package com.ender.takehome.controller

import com.ender.takehome.dto.response.RentChargeResponse
import com.ender.takehome.model.RentChargeStatus
import com.ender.takehome.service.RentChargeService
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/rent-charges")
class RentChargeController(private val rentChargeService: RentChargeService) {

    @GetMapping("/{id}")
    fun get(@PathVariable id: Long): RentChargeResponse =
        RentChargeResponse.from(rentChargeService.getById(id))

    @GetMapping(params = ["leaseId"])
    fun getByLease(@RequestParam leaseId: Long, pageable: Pageable): Page<RentChargeResponse> =
        rentChargeService.getByLeaseId(leaseId, pageable).map { RentChargeResponse.from(it) }

    @GetMapping(params = ["leaseId", "status"])
    fun getByLeaseAndStatus(
        @RequestParam leaseId: Long,
        @RequestParam status: RentChargeStatus,
        pageable: Pageable,
    ): Page<RentChargeResponse> {
        return if (status == RentChargeStatus.PENDING) {
            rentChargeService.getPendingByLeaseId(leaseId, pageable)
        } else {
            rentChargeService.getByLeaseId(leaseId, pageable)
        }.map { RentChargeResponse.from(it) }
    }
}
