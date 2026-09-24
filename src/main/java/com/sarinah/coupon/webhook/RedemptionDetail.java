package com.sarinah.coupon.webhook;

/**
 * Detail transaksi POS untuk event coupon.redeemed. Semua field opsional di spec,
 * jadi bila order history gagal diambil, webhook tetap dikirim tanpa field ini.
 */
public record RedemptionDetail(
        String storeId,                                   // order.location
        String transactionId,                             // order.order_ref
        CouponWebhookPayload.DiscountApplied discount     // baris order_lines bernilai minus
) {
    public static final RedemptionDetail EMPTY = new RedemptionDetail(null, null, null);
}
