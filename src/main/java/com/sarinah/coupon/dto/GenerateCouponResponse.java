package com.sarinah.coupon.dto;

import com.sarinah.coupon.entity.CouponStatus;

public record GenerateCouponResponse(
        String transactionId,
        String couponCode,
        String couponName,
        Double discountPercentage,
        String scanType,
        CouponStatus status,        // ACTIVE / REDEEMED / EXPIRED / REVOKED
        String startTime,
        String endTime,
        String redeemDate,          // kapan ditebus; null bila belum
        String termsText,
        String createdAt
) {}
