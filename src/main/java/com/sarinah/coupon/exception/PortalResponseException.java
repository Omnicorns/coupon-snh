package com.sarinah.coupon.exception;

/** Portal membalas non-2xx, bukan JSON, atau strukturnya tidak sesuai → jangan ubah data apa pun. */
public class PortalResponseException extends RuntimeException {
    public PortalResponseException(String message) {
        super(message);
    }
}
