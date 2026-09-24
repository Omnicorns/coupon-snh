package com.sarinah.coupon.webhook;

import com.sarinah.coupon.configuration.WebhookOutboxDispatcher;
import com.sarinah.coupon.entity.WebhookOutbox;
import com.sarinah.coupon.entity.WebhookOutboxStatus;
import com.sarinah.coupon.repository.WebhookOutboxRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sarinah.coupon.entity.GeneratedCoupon;
import com.sarinah.coupon.repository.GeneratedCouponRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Menyimpan perubahan kupon + mendaftarkan event webhook dalam SATU transaksi.
 * Pengiriman HTTP dilakukan terpisah oleh {@link WebhookOutboxDispatcher}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CouponWebhookPublisher {

    private static final ZoneId ZONE = ZoneId.of("Asia/Jakarta");

    private final GeneratedCouponRepository generatedCouponRepository;
    private final WebhookOutboxRepository outboxRepository;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final PrimeWebhookProperties props;

    /** Untuk coupon.expired / coupon.revoked (tanpa detail transaksi). */
    @Transactional
    public void saveAndPublish(GeneratedCoupon coupon, WebhookEventType type,
                               String couponId, LocalDateTime occurredAt) {
        saveAndPublish(coupon, type, couponId, occurredAt, RedemptionDetail.EMPTY);
    }

    /**
     * @param couponId   ID template kupon di portal (field "id", mis. "259") = couponId di PRIME.
     * @param occurredAt waktu kejadian (Asia/Jakarta, tanpa offset). Null -> PRIME pakai waktu terima.
     * @param detail     storeId / transactionId / discountApplied; boleh EMPTY.
     */
    @Transactional
    public void saveAndPublish(GeneratedCoupon coupon, WebhookEventType type, String couponId,
                               LocalDateTime occurredAt, RedemptionDetail detail) {
        generatedCouponRepository.save(coupon);

        if (!props.isEnabled()) {
            return;
        }
        String code = coupon.getCouponCode();
        if (code == null || code.isBlank()) {
            log.warn("Webhook: kupon id={} tanpa couponCode, event {} tidak dikirim",
                    coupon.getId(), type.getValue());
            return;
        }
        // Satu event per (kode, jenis event). Sync yang berulang tidak membuat event ganda.
        if (outboxRepository.existsByCouponCodeAndEvent(code, type.getValue())) {
            log.debug("Webhook: {} untuk code={} sudah terdaftar, dilewati", type.getValue(), code);
            return;
        }

        RedemptionDetail d = detail != null ? detail : RedemptionDetail.EMPTY;
        CouponWebhookPayload payload = new CouponWebhookPayload(
                type.getValue(),
                couponId,                 // "259", bukan sku
                code,
                d.storeId(),
                d.transactionId(),
                d.discount(),
                toIsoOffset(occurredAt));

        String body;
        try {
            body = MAPPER.writeValueAsString(payload); // serialize SEKALI
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Gagal serialize payload webhook code=" + code, e);
        }

        Instant now = Instant.now();
        WebhookOutbox outbox = new WebhookOutbox();
        outbox.setCouponCode(code);
        outbox.setEvent(type.getValue());
        outbox.setBody(body);
        outbox.setStatus(WebhookOutboxStatus.PENDING);
        outbox.setAttempts(0);
        outbox.setNextAttemptAt(now);
        outbox.setCreatedAt(now);
        outboxRepository.save(outbox);

        log.info("Webhook: {} untuk code={} masuk antrean: {}", type.getValue(), code, body);
    }

    private static String toIsoOffset(LocalDateTime t) {
        if (t == null) return null;
        // -> 2026-09-23T20:18:49+07:00
        return t.withNano(0).atZone(ZONE).toOffsetDateTime()
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }
}
