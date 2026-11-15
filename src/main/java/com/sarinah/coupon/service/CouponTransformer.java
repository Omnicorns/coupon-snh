package com.sarinah.coupon.service;

import com.sarinah.coupon.dto.CouponResponse;
import com.sarinah.coupon.dto.CouponResponse.*;
import com.sarinah.coupon.entity.Coupon;
import com.sarinah.coupon.util.PortalTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
public class CouponTransformer {

    private final PortalTime time;
    private static final String MERCHANT = "Sarinah";

    public CouponResponse toResponse(Coupon c) {
        boolean isPercentage = c.getDiscPercentage() != null && c.getDiscPercentage() > 0;

        return new CouponResponse(
                String.valueOf(c.getId()),
                c.getName(),
                isPercentage ? "PERCENTAGE_VOUCHER" : "FIXED_VOUCHER",
                c.getDiscPercentage(),
                c.getTypeScan(),                 // scanType
                c.getActive(),
                toIso(c.getStartDate()),
                toIso(c.getEndDate()),
                new UsageRules(
                        new Money("IDR", str(c.getMinimumTransaction())),
                        new Money("IDR", str(c.getMaxDiscount()))
                ),
                c.getDays(),
                new DisplayInfo(MERCHANT, c.getName())
        );
    }

    private String toIso(LocalDateTime dt) {
        return time.toIsoOffset(dt);
    }

    private String str(Number n) {
        return n == null ? null : String.valueOf(n);
    }
}
