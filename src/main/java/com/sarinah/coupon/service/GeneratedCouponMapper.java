package com.sarinah.coupon.service;

import com.sarinah.coupon.dto.GenerateCouponResponse;
import com.sarinah.coupon.entity.GeneratedCoupon;
import com.sarinah.coupon.util.PortalTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
public class GeneratedCouponMapper {

    private final PortalTime time;

    public GenerateCouponResponse toResponse(GeneratedCoupon c) {

        return new GenerateCouponResponse(
                c.getTransactionId(),
                c.getCouponCode(),
                c.getCouponName(),
                c.getDiscPercentage(),
                c.getScanType(),
                c.getStatus(),
                toIso(c.getStartDate()),
                toIso(c.getEndDate()),
                toIso(c.getRedeemDate()),
                c.getTermsText(),
                c.getCreatedAt() == null ? null : c.getCreatedAt().toString()
        );
    }

    private String toIso(LocalDateTime dt) {
        return time.toIsoOffset(dt);
    }
}
