package com.sarinah.coupon.webhook;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** Nilai field "event" yang diterima PRIME (spec bagian 3). */
@Getter
@RequiredArgsConstructor
public enum WebhookEventType{
    REDEEMED("coupon.redeemed"),
    EXPIRED("coupon.expired"),
    REVOKED("coupon.revoked");

    private final String value;
}
