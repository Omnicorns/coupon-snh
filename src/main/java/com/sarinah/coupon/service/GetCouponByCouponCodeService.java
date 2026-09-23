package com.sarinah.coupon.service;

import com.sarinah.coupon.dto.GenerateCouponByCouponResponse;
import com.sarinah.coupon.repository.GeneratedCouponRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class GetCouponByCouponCodeService {
    private final GeneratedCouponRepository generatedCouponRepository;
    private final GenerateCouponCodeMapper  transformer;


    public GenerateCouponByCouponResponse getByCouponCode(String couponCode) {
        return generatedCouponRepository.findByCouponCode(couponCode)
                .map(transformer::toResponse)
                .orElseThrow(() -> new RuntimeException("Generated coupon not found for coupon code: " + couponCode));
    }



}
