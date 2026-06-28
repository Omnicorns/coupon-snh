package com.sarinah.coupon.dto;

import java.util.List;

/**
 * Kontrak publik GET coupon untuk pihak ketiga.
 */
public record CouponResponse(
        String couponId,
        String name,
        String voucherType,
        Double discountPercentage,
        String scanType,            // type_scan, "QR"
        Boolean active,
        String startTime,
        String endTime,
        UsageRules usageRules,
        List<String> validDays,
        DisplayInfo displayInfo
) {
    public record Money(String currency, String value) {}

    public record UsageRules(Money minPayAmount, Money maxDiscount) {}

    public record DisplayInfo(String merchantName, String description) {}
}
