package com.sarinah.coupon.service;

import com.sarinah.coupon.dto.CouponResponse;
import com.sarinah.coupon.dto.CouponResponse.*;
import com.sarinah.coupon.entity.Coupon;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

@Component
public class CouponTransformer {

    private static final ZoneId JKT = ZoneId.of("Asia/Jakarta");
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
        return dt == null ? null
                : dt.atZone(JKT).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    private String str(Number n) {
        return n == null ? null : String.valueOf(n);
    }
}
