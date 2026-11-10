package com.sarinah.coupon.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Klaim idempotency untuk generate kupon.
 *
 * Baris ini disisipkan (INSERT ... ON CONFLICT DO NOTHING) dan di-commit SEBELUM portal dipanggil,
 * sehingga dari sekian request bersamaan dengan idempotencyKey yang sama hanya satu yang
 * memegang klaim dan memanggil portal.
 */
@Entity
@Table(name = "coupon_generate_requests")
@Getter
@Setter
@NoArgsConstructor
public class CouponGenerateRequest {

    @Id
    @Column(name = "idempotency_key", nullable = false, length = 255)
    private String idempotencyKey;

    /** SHA-256 dari isi request (couponId, transactionId, tag) — mendeteksi key dipakai ulang untuk request lain. */
    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private GenerateRequestStatus status;

    /** Token pemilik klaim; hanya pemegang token yang boleh menyelesaikan / melepas klaim. */
    @Column(name = "claim_token", length = 36)
    private String claimToken;

    /** Batas lease PROCESSING; lewat dari ini klaim dianggap basi dan boleh diambil alih. */
    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "generated_coupon_id")
    private Long generatedCouponId;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
