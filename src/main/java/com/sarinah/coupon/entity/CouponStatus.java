package com.sarinah.coupon.entity;

public enum CouponStatus {
    ACTIVE,     // baru diterbitkan, siap dipakai
    REDEEMED,   // sudah dipakai
    EXPIRED,    // kedaluwarsa
    REVOKED,   // dibatalkan
}
