package com.sarinah.coupon.exception;

public class CouponExhaustedException extends RuntimeException {
    public CouponExhaustedException(Long couponId, String code) {
        super("Kupon untuk coupon " + couponId + " sudah habis:");
    }
}
