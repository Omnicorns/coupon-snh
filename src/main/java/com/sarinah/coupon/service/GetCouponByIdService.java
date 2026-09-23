package com.sarinah.coupon.service;

import com.sarinah.coupon.dto.GenerateCouponResponse;
import com.sarinah.coupon.repository.GeneratedCouponRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class GetCouponByIdService {
    private final GeneratedCouponRepository generatedCouponRepository;



    public GenerateCouponResponse getCouponById(Long id) {
        var coupon = generatedCouponRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Coupon not found"));

        return new GenerateCouponResponse(
                coupon.getCouponId(),
                coupon.getTransactionId(),
                coupon.getCouponCode(),
                coupon.getCouponName(),
                coupon.getDiscPercentage(),
                coupon.getScanType(),
                coupon.getStartDate() == null ? null : coupon.getStartDate().toString(),
                coupon.getEndDate() == null ? null : coupon.getEndDate().toString(),
                coupon.getRedeemDate() == null ? null : coupon.getRedeemDate().toString(),
                coupon.getTermsText(),
                coupon.getCreatedAt() == null ? null : coupon.getCreatedAt().toString()
        );
    }
}
