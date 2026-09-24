package com.sarinah.coupon.webhook;

import com.sarinah.coupon.configuration.HmacSigner;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;

/**
 * Mengirim satu body JSON (yang sudah di-serialize) ke PRIME.
 * Memakai java.net.http.HttpClient supaya byte yang dikirim persis string yang ditandatangani
 * dan tidak ada header Origin (spec bagian 1: Origin -> 403).
 */
@Slf4j
@Component
public class PrimeWebhookClient {

    private static final int MAX_DETAIL = 1000;

    private final PrimeWebhookProperties props;
    private final HttpClient http;

    public PrimeWebhookClient(PrimeWebhookProperties props) {
        this.props = props;
        this.http = HttpClient.newBuilder()
                .connectTimeout(props.getConnectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public DeliveryResult send(String body) {
        String secret = props.getSecret();
        if (secret == null || secret.isBlank()) {
            // Salah konfigurasi di sisi kita: tetap PENDING, terkirim setelah secret diisi.
            return new DeliveryResult(Outcome.RETRYABLE, null, "prime.webhook.secret belum dikonfigurasi");
        }


        String signature = HmacSigner.sign(secret, body);

        HttpRequest request = HttpRequest.newBuilder(URI.create(props.getUrl()))
                .timeout(props.getReadTimeout())
                .header("Content-Type", "application/json")
                .header("X-Sarinah-Signature", signature)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        try {
            HttpResponse<String> res = http.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int code = res.statusCode();
            String detail = truncate(res.body());

            if (code >= 200 && code < 300) {
                return new DeliveryResult(Outcome.SUCCESS, code, detail);
            }
            if (code >= 500) {
                return new DeliveryResult(Outcome.RETRYABLE, code, detail);
            }
            // 400/401/403/413 dan lainnya: jangan retry apa adanya (spec 4.2)
            return new DeliveryResult(Outcome.PERMANENT_FAILURE, code, detail);

        } catch (HttpTimeoutException e) {
            return new DeliveryResult(Outcome.RETRYABLE, null, "timeout: " + e.getMessage());
        } catch (IOException e) {
            return new DeliveryResult(Outcome.RETRYABLE, null, "gagal koneksi: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new DeliveryResult(Outcome.RETRYABLE, null, "interrupted");
        }
    }

    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() <= MAX_DETAIL ? s : s.substring(0, MAX_DETAIL);
    }

    public enum Outcome { SUCCESS, RETRYABLE, PERMANENT_FAILURE }

    public record DeliveryResult(Outcome outcome, Integer httpStatus, String detail) {
    }
}
