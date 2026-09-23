package com.sarinah.coupon.controller;

import com.sarinah.coupon.dto.GenerateCouponByCouponResponse;
import com.sarinah.coupon.dto.GenerateCouponNewResponse;
import com.sarinah.coupon.dto.GenerateCouponRequest;
import com.sarinah.coupon.dto.GenerateCouponResponse;
import com.sarinah.coupon.repository.GeneratedCouponRepository;
import com.sarinah.coupon.service.CouponGenerateService;
import com.sarinah.coupon.service.GeneratedCouponMapper;
import com.sarinah.coupon.service.GetCouponByCouponCodeService;
import com.sarinah.coupon.service.GetCouponByIdService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * POST /api/v1/coupon/generate     → terbitkan kode kupon dari sebuah coupon sumber
 * GET  /api/v1/coupon/generated    → daftar kupon terbit yang belum dipakai (is_used disembunyikan)
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

    @PostMapping("/generate")
    public GenerateCouponResponse generate(@RequestBody GenerateCouponRequest request) {
        return generateService.generate(request);
    }

    @GetMapping("/generated")
    public List<GenerateCouponResponse> listAvailable(
            @RequestParam(required = false) String tag) {

        var data = (tag == null || tag.isBlank())
                ? generatedRepo.findByIsUsedFalse()
                : generatedRepo.findByTagAndIsUsedFalse(tag);

        return data.stream().map(mapper::toResponse).toList();
    }

    @GetMapping("/{id}")
    public List<GenerateCouponNewResponse> getById(@PathVariable Long id) {
        return getCouponByIdService.getCouponById(id);
    }

    @GetMapping("/{code}")
    public GenerateCouponByCouponResponse getByCode(@PathVariable String code) {
        return getCouponByCouponCodeService.getByCouponCode(code);
    }


}
