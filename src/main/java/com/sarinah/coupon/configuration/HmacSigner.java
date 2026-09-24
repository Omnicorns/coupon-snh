package com.sarinah.coupon.configuration;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;

public class HmacSigner {
    private static final String ALGO = "HmacSHA256";

    private HmacSigner() {
    }

    public static String sign(String secret, String body) {
        try {
            Mac mac = Mac.getInstance(ALGO);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGO));
            byte[] raw = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(raw); // lowercase
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Gagal membuat HMAC-SHA256", e);
        }
    }

}
