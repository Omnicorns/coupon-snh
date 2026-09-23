package com.sarinah.coupon.dto;

import com.sarinah.coupon.entity.CouponStatus;

public record GenerateCouponByCouponResponse(
        String couponCode,
        String couponName,
        CouponStatus status,
        Boolean isUsed,
        String redeemDate


) {}
