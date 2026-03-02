package com.ender.takehome.controller

import com.ender.takehome.dto.response.CursorPage
import com.ender.takehome.dto.response.RentChargeResponse
import com.ender.takehome.model.RentChargeStatus
import com.ender.takehome.service.RentChargeService
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/rent-charges")
class RentChargeController(private val rentChargeService: RentChargeService) {

    @GetMapping("/{id}")
    fun get(@PathVariable id: Long): RentChargeResponse =
        RentChargeResponse.from(rentChargeService.getById(id))

    @GetMapping(params = ["leaseId"])
    fun getByLease(
        @RequestParam leaseId: Long,
        @RequestParam(required = false) startAfterId: Long?,
        @RequestParam(defaultValue = "20") limit: Int,
    ): CursorPage<RentChargeResponse> {
        val page = rentChargeService.getByLeaseId(leaseId, startAfterId, limit)
        return CursorPage(page.content.map { RentChargeResponse.from(it) }, page.hasMore)
    }

    @GetMapping(params = ["leaseId", "status"])
    fun getByLeaseAndStatus(
        @RequestParam leaseId: Long,
        @RequestParam status: RentChargeStatus,
        @RequestParam(required = false) startAfterId: Long?,
        @RequestParam(defaultValue = "20") limit: Int,
    ): CursorPage<RentChargeResponse> {
        val page = if (status == RentChargeStatus.PENDING) {
            rentChargeService.getPendingByLeaseId(leaseId, startAfterId, limit)
        } else {
            rentChargeService.getByLeaseId(leaseId, startAfterId, limit)
        }
        return CursorPage(page.content.map { RentChargeResponse.from(it) }, page.hasMore)
    }
}
