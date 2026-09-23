package com.sarinah.coupon.dto;

import com.sarinah.coupon.entity.CouponStatus;

public record GenerateCouponNewResponse(
        String transactionId,
        String couponCode,
        String couponName,
        Double discountPercentage,
        String scanType,
        CouponStatus status,
        Boolean isUsed,
        String startTime,
        String endTime,
        String redeemDate,          // kapan ditebus; null bila belum
        String termsText,
        String createdAt
) {}
