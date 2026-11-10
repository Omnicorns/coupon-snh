package com.sarinah.coupon.repository;

import com.sarinah.coupon.entity.WebhookOutbox;
import com.sarinah.coupon.entity.WebhookOutboxStatus;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

public interface WebhookOutboxRepository extends JpaRepository<WebhookOutbox, Long> {

    boolean existsByCouponCodeAndEvent(String couponCode, String event);

    List<WebhookOutbox> findByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(
            WebhookOutboxStatus status, Instant now, Pageable pageable);

    List<WebhookOutbox> findByStatusOrderByCreatedAtDesc(WebhookOutboxStatus status, Pageable pageable);

    /**
     * Klaim atomik event PENDING yang jatuh tempo untuk satu putaran dispatcher.
     * FOR UPDATE SKIP LOCKED + lease membuat setiap event hanya diklaim oleh satu instance,
     * sementara instance lain mengambil event berikutnya tanpa saling menunggu.
     */
    @Modifying
    @Transactional
    @Query(value = """
            UPDATE prime_webhook_outbox
               SET locked_by = :owner, locked_until = :lockedUntil
             WHERE id IN (
                   SELECT id FROM prime_webhook_outbox
                    WHERE status = 'PENDING'
                      AND next_attempt_at <= :now
                      AND (locked_until IS NULL OR locked_until < :now)
                    ORDER BY next_attempt_at
                    LIMIT :limit
                    FOR UPDATE SKIP LOCKED)
            """, nativeQuery = true)
    int claimDue(@Param("owner") String owner,
                 @Param("now") Instant now,
                 @Param("lockedUntil") Instant lockedUntil,
                 @Param("limit") int limit);

    List<WebhookOutbox> findByLockedByOrderByNextAttemptAtAsc(String lockedBy);

    /** Simpan hasil percobaan + lepas lock — hanya bila putaran ini masih pemegang lock. */
    @Modifying
    @Transactional
    @Query("""
            update WebhookOutbox o
               set o.status = :status, o.attempts = :attempts, o.lastAttemptAt = :lastAttemptAt,
                   o.lastHttpStatus = :lastHttpStatus, o.lastError = :lastError,
                   o.nextAttemptAt = :nextAttemptAt, o.sentAt = :sentAt,
                   o.lockedBy = null, o.lockedUntil = null
             where o.id = :id and o.lockedBy = :owner
            """)
    int finishAttempt(@Param("id") Long id,
                      @Param("owner") String owner,
                      @Param("status") WebhookOutboxStatus status,
                      @Param("attempts") int attempts,
                      @Param("lastAttemptAt") Instant lastAttemptAt,
                      @Param("lastHttpStatus") Integer lastHttpStatus,
                      @Param("lastError") String lastError,
                      @Param("nextAttemptAt") Instant nextAttemptAt,
                      @Param("sentAt") Instant sentAt);

    /** Lepas lock baris yang belum sempat diproses di putaran ini. */
    @Modifying
    @Transactional
    @Query("update WebhookOutbox o set o.lockedBy = null, o.lockedUntil = null where o.lockedBy = :owner")
    int releaseAll(@Param("owner") String owner);
}
