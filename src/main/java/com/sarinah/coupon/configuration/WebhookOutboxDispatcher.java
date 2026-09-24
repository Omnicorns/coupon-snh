package com.sarinah.coupon.configuration;

import com.sarinah.coupon.entity.WebhookOutbox;
import com.sarinah.coupon.entity.WebhookOutboxStatus;
import com.sarinah.coupon.repository.WebhookOutboxRepository;
import com.sarinah.coupon.webhook.PrimeWebhookClient;
import com.sarinah.coupon.webhook.PrimeWebhookProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Mengirim event PENDING yang sudah jatuh tempo, dengan retry sesuai spec 4.2:
 * 1m, 5m, 15m, 1j, lalu per jam, maksimal 24 jam. Retry hanya untuk 5xx/timeout/gagal koneksi.
 *
 * Catatan: bila aplikasi jalan di lebih dari satu instance, pasang ShedLock (atau sejenisnya)
 * pada method dispatchDue() agar event tidak dikirim dua kali secara bersamaan.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebhookOutboxDispatcher {

    private static final List<Duration> BACKOFF = List.of(
            Duration.ofMinutes(1),
            Duration.ofMinutes(5),
            Duration.ofMinutes(15),
            Duration.ofHours(1));
    private static final Duration AFTER_BACKOFF = Duration.ofHours(1);

    private final WebhookOutboxRepository outboxRepository;
    private final PrimeWebhookClient client;
    private final PrimeWebhookProperties props;

    @Scheduled(fixedDelayString = "${prime.webhook.dispatch-interval-ms:30000}",
            initialDelayString = "${prime.webhook.dispatch-initial-delay-ms:15000}")
    public void dispatchDue() {
        if (!props.isEnabled()) return;

        List<WebhookOutbox> due = outboxRepository
                .findByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(
                        WebhookOutboxStatus.PENDING, Instant.now(), PageRequest.of(0, props.getBatchSize()));

        for (WebhookOutbox item : due) {
            try {
                deliver(item);
            } catch (Exception e) {
                log.error("Webhook: error tak terduga saat kirim id={}: {}", item.getId(), e.getMessage(), e);
            }
        }
    }

    void deliver(WebhookOutbox item) {
        Instant now = Instant.now();
        PrimeWebhookClient.DeliveryResult r = client.send(item.getBody());

        item.setAttempts(item.getAttempts() + 1);
        item.setLastAttemptAt(now);
        item.setLastHttpStatus(r.httpStatus());
        item.setLastError(r.outcome() == PrimeWebhookClient.Outcome.SUCCESS ? null : r.detail());

        switch (r.outcome()) {
            case SUCCESS -> {
                item.setStatus(WebhookOutboxStatus.SENT);
                item.setSentAt(now);
                log.info("Webhook: {} code={} terkirim (HTTP {}) {}",
                        item.getEvent(), item.getCouponCode(), r.httpStatus(), r.detail());
            }
            case PERMANENT_FAILURE -> {
                item.setStatus(WebhookOutboxStatus.FAILED);
                log.error("Webhook: {} code={} DITOLAK (HTTP {}), tidak di-retry: {}",
                        item.getEvent(), item.getCouponCode(), r.httpStatus(), r.detail());
            }
            case RETRYABLE -> {
                Instant next = now.plus(backoff(item.getAttempts()));
                Instant deadline = item.getCreatedAt().plus(props.getMaxRetryWindow());
                if (next.isAfter(deadline)) {
                    item.setStatus(WebhookOutboxStatus.FAILED);
                    log.error("Webhook: {} code={} gagal selama {} (percobaan {}), berhenti: {}",
                            item.getEvent(), item.getCouponCode(), props.getMaxRetryWindow(),
                            item.getAttempts(), r.detail());
                } else {
                    item.setNextAttemptAt(next);
                    log.warn("Webhook: {} code={} gagal (HTTP {}), retry pada {}: {}",
                            item.getEvent(), item.getCouponCode(), r.httpStatus(), next, r.detail());
                }
            }
        }
        outboxRepository.save(item);
    }

    static Duration backoff(int attemptsSoFar) {
        int idx = attemptsSoFar - 1;
        return (idx >= 0 && idx < BACKOFF.size()) ? BACKOFF.get(idx) : AFTER_BACKOFF;
    }
}
