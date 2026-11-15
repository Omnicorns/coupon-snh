package com.sarinah.coupon.controller;

import com.sarinah.coupon.configuration.ApiKeyConfig;
import com.sarinah.coupon.dto.GenerateCouponByCouponResponse;
import com.sarinah.coupon.dto.GenerateCouponNewResponse;
import com.sarinah.coupon.dto.GenerateCouponRequest;
import com.sarinah.coupon.dto.GenerateCouponResponse;
import com.sarinah.coupon.repository.GeneratedCouponRepository;
import com.sarinah.coupon.service.CouponGenerateService;
import com.sarinah.coupon.service.GeneratedCouponMapper;
import com.sarinah.coupon.service.GetCouponByCouponCodeService;
import com.sarinah.coupon.service.GetCouponByIdService;
import com.sarinah.coupon.util.PortalTime;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * POST /api/v1/coupon/generate     → terbitkan kode kupon dari sebuah coupon sumber
 * GET  /api/v1/coupon/generated    → kupon milik partner yang masih bisa dipakai
 *
 * Semua endpoint dibatasi ke partner hasil autentikasi API key ({@link ApiKeyConfig#PARTNER_ATTR});
 * partner tidak pernah bisa melihat kupon partner lain.
 */
@RestController
@RequestMapping("/api/v1/coupon")
@RequiredArgsConstructor
public class CouponGenerateController {

    private final CouponGenerateService generateService;
    private final GeneratedCouponRepository generatedRepo;
    private final GeneratedCouponMapper mapper;
    private final GetCouponByIdService getCouponByIdService;
    private final GetCouponByCouponCodeService getCouponByCouponCodeService;
    private final PortalTime time;

    @PostMapping("/generate")
    public GenerateCouponResponse generate(@RequestAttribute(ApiKeyConfig.PARTNER_ATTR) String partnerId,
                                           @RequestBody GenerateCouponRequest request) {
        return generateService.generate(partnerId, request);
    }

    /** Hanya kupon ACTIVE, belum dipakai, dan dalam periode berlaku; tag hanya menyaring di dalam milik partner. */
    @GetMapping("/generated")
    public List<GenerateCouponResponse> listAvailable(
            @RequestAttribute(ApiKeyConfig.PARTNER_ATTR) String partnerId,
            @RequestParam(required = false) String tag) {

        var now = time.now();
        var data = (tag == null || tag.isBlank())
                ? generatedRepo.findAvailable(partnerId, now)
                : generatedRepo.findAvailableByTag(partnerId, tag, now);

        return data.stream().map(mapper::toResponse).toList();
    }

    @GetMapping("/{id}")
    public List<GenerateCouponNewResponse> getById(@RequestAttribute(ApiKeyConfig.PARTNER_ATTR) String partnerId,
                                                   @PathVariable Long id) {
        return getCouponByIdService.getCouponById(partnerId, id);
    }

    @GetMapping("/code/{code}")
    public GenerateCouponByCouponResponse getByCode(@RequestAttribute(ApiKeyConfig.PARTNER_ATTR) String partnerId,
                                                    @PathVariable String code) {
        return getCouponByCouponCodeService.getByCouponCode(partnerId, code);
    }
}
