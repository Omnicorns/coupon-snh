package com.sarinah.coupon.dto;

public record GenerateCouponResponse(
        Long generateCouponId,
        String transactionId,
        String couponCode,
        String couponName,
        Double discountPercentage,
        String scanType,
        String startTime,
        String endTime,
        String redeemDate,          // kapan ditebus; null bila belum
        String termsText,
        String createdAt
) {}
