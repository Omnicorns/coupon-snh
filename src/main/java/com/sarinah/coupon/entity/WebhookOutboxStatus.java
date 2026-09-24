package com.sarinah.coupon.entity;

public enum WebhookOutboxStatus {
    /** Menunggu dikirim / menunggu retry. */
    PENDING,
    /** PRIME membalas 2xx. Jangan dikirim ulang. */
    SENT,
    /** 4xx, atau 5xx/timeout yang melewati jendela retry 24 jam. Perlu dicek manual. */
    FAILED
}
