package com.sarinah.coupon.service;

import com.sarinah.coupon.dto.GenerateCouponByCouponResponse;
import com.sarinah.coupon.dto.GenerateCouponResponse;
import com.sarinah.coupon.entity.GeneratedCoupon;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

@Component
public class GenerateCouponCodeMapper {
    private static final ZoneId JKT = ZoneId.of("Asia/Jakarta");

    public GenerateCouponByCouponResponse toResponse(GeneratedCoupon c) {

        return new GenerateCouponByCouponResponse(
                c.getCouponCode(),
                c.getCouponName(),
                c.getStatus(),
                c.getIsUsed(),
                c.getRedeemDate() == null ? null : c.getRedeemDate().toString()


        );
    }

    private String toIso(LocalDateTime dt) {
        return dt == null ? null
                : dt.atZone(JKT).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }
}
