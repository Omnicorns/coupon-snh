package com.sarinah.coupon.controller;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.sarinah.coupon.dto.CouponResponse;
import com.sarinah.coupon.service.CouponQueryService;
import com.sarinah.coupon.service.CouponSyncService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * API publik untuk pihak ketiga. Hanya GET, hanya mengembalikan DTO
 * (kontrak publik) — tidak pernah membocorkan entity mentah / rawJson.
 */
@RestController
@RequestMapping("/api/v1/coupons")
@RequiredArgsConstructor
public class CouponController {

    private final CouponQueryService queryService;
    private final CouponSyncService syncService;

    @GetMapping
    public List<CouponResponse> list() {
        return queryService.getActive();
    }

    @GetMapping("/all")
    public List<CouponResponse> listAll() {
        return queryService.getAll();
    }

    @GetMapping("/valid-now")
    public List<CouponResponse> validNow() {
        return queryService.getValidNow();
    }

    @GetMapping("/{id}")
    public CouponResponse detail(@PathVariable Long id) {
        return queryService.getById(id);
    }

    /**
     * Pemicu sync manual untuk keperluan operasional/testing.
     * Tidak menerima @RequestBody (menghindari masalah deserialisasi ObjectNode
     * di Jackson 3) dan idealnya dilindungi agar tidak terbuka untuk publik.
     */
    @PostMapping("/internal/sync")
    public String triggerSync() {
        syncService.sync(JsonNodeFactory.instance.objectNode());
        return "sync dijalankan";
    }
}
