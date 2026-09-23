package com.sarinah.coupon.service;

import com.sarinah.coupon.dto.GenerateCouponResponse;
import com.sarinah.coupon.entity.GeneratedCoupon;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

@Component
public class GeneratedCouponMapper {

    private static final ZoneId JKT = ZoneId.of("Asia/Jakarta");

    public GenerateCouponResponse toResponse(GeneratedCoupon c) {

        return new GenerateCouponResponse(
                c.getId(),
                c.getTransactionId(),
                c.getCouponCode(),
                c.getCouponName(),
                c.getDiscPercentage(),
                c.getScanType(),
                toIso(c.getStartDate()),
                toIso(c.getEndDate()),
                toIso(c.getRedeemDate()),
                c.getTermsText(),
                c.getCreatedAt() == null ? null : c.getCreatedAt().toString()
        );
    }

    private String toIso(LocalDateTime dt) {
        return dt == null ? null
                : dt.atZone(JKT).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }
}
