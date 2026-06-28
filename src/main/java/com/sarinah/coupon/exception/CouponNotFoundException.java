package com.sarinah.coupon.exception;

public class CouponNotFoundException extends RuntimeException {
    public CouponNotFoundException(Long id) {
        super("Coupon tidak ditemukan: " + id);
    }
}
