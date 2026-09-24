package com.sarinah.coupon.entity;



import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * O
 * Body disimpan sebagai string yang sudah di-serialize SEKALI, jadi byte yang ditandatangani
 * selalu sama dengan byte yang dikirim, termasuk saat retry.
 */
@Entity
@Table(name = "prime_webhook_outbox",
        uniqueConstraints = @UniqueConstraint(name = "uk_prime_outbox_code_event",
                columnNames = {"coupon_code", "event"}),
        indexes = @Index(name = "idx_prime_outbox_due", columnList = "status, next_attempt_at"))
@Getter
@Setter
@NoArgsConstructor
public class WebhookOutbox {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "coupon_code", nullable = false, length = 100)
    private String couponCode;

    @Column(name = "event", nullable = false, length = 40)
    private String event;

    @Column(name = "body", nullable = false, columnDefinition = "TEXT")
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private WebhookOutboxStatus status;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "last_attempt_at")
    private Instant lastAttemptAt;

    @Column(name = "last_http_status")
    private Integer lastHttpStatus;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "sent_at")
    private Instant sentAt;
}

