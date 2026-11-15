package com.sarinah.coupon.service;

import com.sarinah.coupon.dto.GenerateCouponByCouponResponse;
import com.sarinah.coupon.dto.GenerateCouponResponse;
import com.sarinah.coupon.entity.GeneratedCoupon;
import com.sarinah.coupon.util.PortalTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
public class GenerateCouponCodeMapper {
    private final PortalTime time;

    public GenerateCouponByCouponResponse toResponse(GeneratedCoupon c) {

        return new GenerateCouponByCouponResponse(
                c.getCouponCode(),
                c.getCouponName(),
                c.getStatus(),
                c.getIsUsed(),
                toIso(c.getRedeemDate())


        );
    }

    private String toIso(LocalDateTime dt) {
        return time.toIsoOffset(dt);
    }
}
