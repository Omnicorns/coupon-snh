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
import java.util.UUID;

/**
 * Mengirim event PENDING yang sudah jatuh tempo, dengan retry sesuai spec 4.2:
 * 1m, 5m, 15m, 1j, lalu per jam, maksimal 24 jam. Retry hanya untuk 5xx/timeout/gagal koneksi.
 *
 * Aman untuk multi-instance: setiap putaran mengklaim baris secara atomik di PostgreSQL
 * (FOR UPDATE SKIP LOCKED + lease locked_until), sehingga satu event hanya diproses oleh satu
 * instance. Hasil percobaan hanya disimpan bila putaran ini masih pemegang lock.
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
    /** Cadangan waktu di atas estimasi durasi batch sebelum lease dianggap habis. */
    private static final Duration LEASE_MARGIN = Duration.ofMinutes(1);

    private final WebhookOutboxRepository outboxRepository;
    private final PrimeWebhookClient client;
    private final PrimeWebhookProperties props;

    @Scheduled(fixedDelayString = "${prime.webhook.dispatch-interval-ms:30000}",
            initialDelayString = "${prime.webhook.dispatch-initial-delay-ms:15000}")
    public void dispatchDue() {
        if (!props.isEnabled()) return;

        String owner = UUID.randomUUID().toString();
        Duration perItem = props.getConnectTimeout().plus(props.getReadTimeout());
        Instant now = Instant.now();
        // Lease cukup untuk seluruh batch walau setiap kiriman kena timeout penuh.
        Instant leaseUntil = now.plus(perItem.multipliedBy(props.getBatchSize())).plus(LEASE_MARGIN);

        int claimed = outboxRepository.claimDue(owner, now, leaseUntil, props.getBatchSize());
        if (claimed == 0) return;

        try {
            for (WebhookOutbox item : outboxRepository.findByLockedByOrderByNextAttemptAtAsc(owner)) {
                // Jangan mulai kiriman yang bisa melewati lease (instance lain bisa mengklaim ulang).
                if (Instant.now().plus(perItem).plus(LEASE_MARGIN).isAfter(leaseUntil)) {
                    log.warn("Webhook: lease putaran {} hampir habis, sisa event dilepas", owner);
                    break;
                }
                try {
                    deliver(item, owner);
                } catch (Exception e) {
                    log.error("Webhook: error tak terduga saat kirim id={}: {}", item.getId(), e.getMessage(), e);
                }
            }
        } finally {
            // Baris yang belum diproses (atau gagal tak terduga) dilepas agar bisa diambil putaran berikutnya.
            outboxRepository.releaseAll(owner);
        }
    }

    void deliver(WebhookOutbox item, String owner) {
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

        int updated = outboxRepository.finishAttempt(item.getId(), owner, item.getStatus(),
                item.getAttempts(), item.getLastAttemptAt(), item.getLastHttpStatus(),
                item.getLastError(), item.getNextAttemptAt(), item.getSentAt());
        if (updated == 0) {
            log.warn("Webhook: lock id={} code={} sudah tidak dipegang putaran {}, hasil tidak disimpan",
                    item.getId(), item.getCouponCode(), owner);
        }
    }

    static Duration backoff(int attemptsSoFar) {
        int idx = attemptsSoFar - 1;
        return (idx >= 0 && idx < BACKOFF.size()) ? BACKOFF.get(idx) : AFTER_BACKOFF;
    }
}
