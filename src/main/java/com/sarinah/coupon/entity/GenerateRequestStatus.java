package com.sarinah.coupon.entity;

/** Status klaim idempotency untuk POST /api/v1/coupon/generate. */
public enum GenerateRequestStatus {
    PROCESSING, // sedang dikerjakan oleh pemegang claim_token sampai locked_until
    COMPLETED,  // kupon sudah terbit → generated_coupon_id terisi, retry mengembalikan hasil yang sama
    FAILED      // gagal sebelum kupon tersimpan → boleh diambil alih oleh retry dengan key yang sama
}
