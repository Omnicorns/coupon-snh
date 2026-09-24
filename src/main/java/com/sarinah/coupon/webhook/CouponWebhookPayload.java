package com.sarinah.coupon.webhook;

import com.fasterxml.jackson.annotation.JsonInclude;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * Body request ke PRIME (spec bagian 3). Field null tidak ikut dikirim.
 * Urutan field disamakan dengan contoh di spec agar mudah dibandingkan.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"event", "couponId", "couponCode", "storeId",
        "transactionId", "discountApplied", "redeemedAt"})
public record CouponWebhookPayload(
        String event,            // wajib
        String couponId,         // ID template, mis. "259"
        String couponCode,       // wajib, kunci pencarian di PRIME
        String storeId,
        String transactionId,
        DiscountApplied discountApplied,
        String redeemedAt        // ISO-8601 dengan offset, mis. 2026-09-22T14:05:11+07:00
) {
    public record DiscountApplied(String currency, String value) {
    }
}

