package com.sarinah.coupon.service;

import com.sarinah.coupon.dto.GenerateCouponNewResponse;
import com.sarinah.coupon.entity.GeneratedCoupon;
import com.sarinah.coupon.util.PortalTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
public class GeneratedCouponNewMapper {
    private final PortalTime time;

    public GenerateCouponNewResponse toResponse(GeneratedCoupon c) {

        return new GenerateCouponNewResponse(
                c.getTransactionId(),
                c.getCouponCode(),
                c.getCouponName(),
                c.getDiscPercentage(),
                c.getScanType(),
                c.getStatus(),
                c.getIsUsed(),
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
