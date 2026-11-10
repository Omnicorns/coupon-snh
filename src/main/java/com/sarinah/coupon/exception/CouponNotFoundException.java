package com.sarinah.coupon.exception;

public class CouponNotFoundException extends RuntimeException {
    public CouponNotFoundException(Long id) {
        super("Coupon tidak ditemukan: " + id);
    }

    /** Untuk pencarian berdasarkan kode kupon (skd) — kode bisa nonnumerik. */
    public CouponNotFoundException(String couponCode) {
        super("Kupon dengan kode '" + couponCode + "' tidak ditemukan");
    }
}
