package com.sarinah.coupon.webhook;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sarinah.coupon.configuration.WebhookOutboxDispatcher;
import com.sarinah.coupon.entity.CouponStatus;
import com.sarinah.coupon.entity.GeneratedCoupon;
import com.sarinah.coupon.entity.WebhookOutbox;
import com.sarinah.coupon.entity.WebhookOutboxStatus;
import com.sarinah.coupon.repository.GeneratedCouponRepository;
import com.sarinah.coupon.repository.WebhookOutboxRepository;
import com.sarinah.coupon.util.PortalTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Set;

/**
 * Mengubah status kupon + mendaftarkan event webhook dalam SATU transaksi.
 * Pengiriman HTTP dilakukan terpisah oleh {@link WebhookOutboxDispatcher}.
 *
 * Perubahan status memakai UPDATE bersyarat sesuai aturan transisi, bukan save() entity yang
 * dibaca sebelumnya: bila instance sync lain sudah lebih dulu mengubah kupon (mis. REDEEMED),
 * update kita tidak mengenai baris apa pun dan event tidak dibuat.
 *
 * Aturan transisi:
 *   ACTIVE  → REDEEMED | EXPIRED | REVOKED
 *   EXPIRED → REDEEMED | REVOKED
 *   REVOKED → REDEEMED          (portal ternyata mencatat kupon dipakai)
 *   REDEEMED → (final)
 *
 * Event SELALU masuk outbox walaupun prime.webhook.enabled=false; flag itu hanya menahan
 * pengiriman di dispatcher, sehingga event tidak hilang saat webhook dinonaktifkan sementara.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CouponWebhookPublisher {

    static final Set<CouponStatus> REDEEMABLE_FROM =
            EnumSet.of(CouponStatus.ACTIVE, CouponStatus.EXPIRED, CouponStatus.REVOKED);
    static final Set<CouponStatus> EXPIRABLE_FROM = EnumSet.of(CouponStatus.ACTIVE);
    static final Set<CouponStatus> REVOCABLE_FROM = EnumSet.of(CouponStatus.ACTIVE, CouponStatus.EXPIRED);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final GeneratedCouponRepository generatedCouponRepository;
    private final WebhookOutboxRepository outboxRepository;
    private final PortalTime time;

    /**
     * @param couponId ID template kupon di portal (field "id", mis. "259") = couponId di PRIME.
     * @return true bila status berubah (dan event masuk antrean)
     */
    @Transactional
    public boolean markRedeemed(GeneratedCoupon coupon, String state, LocalDateTime redeemDate,
                                String couponId, RedemptionDetail detail) {
        int n = generatedCouponRepository.markRedeemed(coupon.getId(), state, redeemDate, REDEEMABLE_FROM);
        if (n == 0) return skipped(coupon, CouponStatus.REDEEMED);
        enqueue(coupon, WebhookEventType.REDEEMED, couponId, redeemDate, detail);
        return true;
    }

    @Transactional
    public boolean markExpired(GeneratedCoupon coupon, String couponId) {
        int n = generatedCouponRepository.transitionStatus(coupon.getId(), CouponStatus.EXPIRED, EXPIRABLE_FROM);
        if (n == 0) return skipped(coupon, CouponStatus.EXPIRED);
        enqueue(coupon, WebhookEventType.EXPIRED, couponId, null, RedemptionDetail.EMPTY);
        return true;
    }

    @Transactional
    public boolean markRevoked(GeneratedCoupon coupon, String couponId) {
        int n = generatedCouponRepository.transitionStatus(coupon.getId(), CouponStatus.REVOKED, REVOCABLE_FROM);
        if (n == 0) return skipped(coupon, CouponStatus.REVOKED);
        enqueue(coupon, WebhookEventType.REVOKED, couponId, null, RedemptionDetail.EMPTY);
        return true;
    }

    private boolean skipped(GeneratedCoupon c, CouponStatus target) {
        log.info("Sync: code={} tidak diubah ke {} (status terbaru di DB tidak mengizinkan / sudah diubah instance lain)",
                c.getCouponCode(), target);
        return false;
    }

    private void enqueue(GeneratedCoupon coupon, WebhookEventType type, String couponId,
                         LocalDateTime occurredAt, RedemptionDetail detail) {
        String code = coupon.getCouponCode();
        if (code == null || code.isBlank()) {
            log.warn("Webhook: kupon id={} tanpa couponCode, event {} tidak dibuat",
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
                occurredAt == null ? null : time.toIsoOffset(occurredAt.withNano(0)));

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
}
