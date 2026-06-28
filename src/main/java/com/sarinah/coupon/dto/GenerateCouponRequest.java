package com.sarinah.coupon.dto;

/**
 * Body POST /api/v1/coupon/generate.
 * - idempotencyKey : wajib, mencegah penerbitan ganda saat retry.
 * - couponId       : wajib, id coupon SUMBER (campaign).
 * - transactionId  : id transaksi dari pihak ketiga (untuk pelacakan).
 * - tag            : opsional, default diturunkan dari campaign.
 */
public record GenerateCouponRequest(
        String idempotencyKey,
        Long couponId,
        String transactionId,
        String tag
) {}
