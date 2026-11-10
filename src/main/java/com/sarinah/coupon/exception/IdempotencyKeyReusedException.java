package com.sarinah.coupon.exception;

/** idempotencyKey yang sama dikirim dengan isi request berbeda → 422. */
public class IdempotencyKeyReusedException extends RuntimeException {
    public IdempotencyKeyReusedException(String key) {
        super("idempotencyKey '" + key + "' sudah dipakai untuk request dengan isi berbeda");
    }
}
