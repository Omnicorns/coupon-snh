package com.sarinah.coupon.repository;

import com.sarinah.coupon.entity.CouponGenerateRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;

@Repository
public interface CouponGenerateRequestRepository extends JpaRepository<CouponGenerateRequest, String> {

    /**
     * Klaim atomik: 1 = klaim baru milik kita, 0 = key sudah ada (milik request lain / sudah selesai).
     * PostgreSQL menjamin hanya satu INSERT yang menang untuk primary key yang sama.
     */
    @Modifying
    @Query(value = """
            INSERT INTO coupon_generate_requests
                (idempotency_key, request_hash, status, claim_token, locked_until, attempts, created_at, updated_at)
            VALUES (:key, :hash, 'PROCESSING', :token, :lockedUntil, 1, :now, :now)
            ON CONFLICT (idempotency_key) DO NOTHING
            """, nativeQuery = true)
    int insertClaim(@Param("key") String key,
                    @Param("hash") String hash,
                    @Param("token") String token,
                    @Param("lockedUntil") Instant lockedUntil,
                    @Param("now") Instant now);

    /**
     * Ambil alih klaim yang FAILED atau PROCESSING-basi (lease habis). Atomik: hanya satu
     * pengambil alih yang mendapat 1 baris.
     */
    @Modifying
    @Query(value = """
            UPDATE coupon_generate_requests
               SET status = 'PROCESSING', claim_token = :token, locked_until = :lockedUntil,
                   attempts = attempts + 1, last_error = NULL, updated_at = :now
             WHERE idempotency_key = :key
               AND request_hash = :hash
               AND (status = 'FAILED' OR (status = 'PROCESSING' AND locked_until < :now))
            """, nativeQuery = true)
    int takeOver(@Param("key") String key,
                 @Param("hash") String hash,
                 @Param("token") String token,
                 @Param("lockedUntil") Instant lockedUntil,
                 @Param("now") Instant now);

    /** Tandai selesai — hanya berhasil bila kita masih pemegang klaim. */
    @Modifying
    @Query(value = """
            UPDATE coupon_generate_requests
               SET status = 'COMPLETED', generated_coupon_id = :couponId,
                   claim_token = NULL, locked_until = NULL, last_error = NULL, updated_at = :now
             WHERE idempotency_key = :key AND claim_token = :token AND status = 'PROCESSING'
            """, nativeQuery = true)
    int complete(@Param("key") String key,
                 @Param("token") String token,
                 @Param("couponId") Long couponId,
                 @Param("now") Instant now);

    /** Lepas klaim sebagai FAILED agar retry dengan key yang sama bisa mencoba lagi. */
    @Modifying
    @Query(value = """
            UPDATE coupon_generate_requests
               SET status = 'FAILED', claim_token = NULL, locked_until = NULL,
                   last_error = :error, updated_at = :now
             WHERE idempotency_key = :key AND claim_token = :token AND status = 'PROCESSING'
            """, nativeQuery = true)
    int release(@Param("key") String key,
                @Param("token") String token,
                @Param("error") String error,
                @Param("now") Instant now);
}
