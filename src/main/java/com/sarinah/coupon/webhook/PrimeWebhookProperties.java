package com.sarinah.coupon.webhook;


import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Data
@Component
@ConfigurationProperties(prefix = "prime.webhook")
public class PrimeWebhookProperties {

    /** Matikan pengiriman tanpa mengubah kode (mis. di environment lokal). */
    private boolean enabled = true;

    private String url = "https://integrated.injourney.id/sarinah/webhook";

    /** Secret HMAC dari tim PRIME (kanal aman). */
    private String secret;

    private Duration connectTimeout = Duration.ofSeconds(5);

    /** Spec: timeout PRIME ±10 detik. */
    private Duration readTimeout = Duration.ofSeconds(10);

    /** Jumlah event yang dikirim per putaran dispatcher. */
    private int batchSize = 50;

    /** Spec 4.2: retry maksimal 24 jam sejak event dibuat. */
    private Duration maxRetryWindow = Duration.ofHours(24);

    private int orderLookupDays = 1;
    private int orderLookupFallbackDays = 7;
}
