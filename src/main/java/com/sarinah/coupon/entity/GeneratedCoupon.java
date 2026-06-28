package com.sarinah.coupon.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDateTime;

/**
 * Kupon hasil GENERATE. Kode kupon = `skd` yang diterbitkan portal Odoo.
 * Menyimpan ringkasan campaign sumber agar response tidak perlu join.
 */
@Entity
@Table(name = "generated_coupons",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_idempotency_key", columnNames = "idempotency_key"),
                @UniqueConstraint(name = "uk_coupon_code", columnNames = "coupon_code")
        })
@Getter
@Setter
@NoArgsConstructor
public class GeneratedCoupon {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;                       // id record kupon terbit

    @Column(name = "coupon_code", nullable = false)
    private String couponCode;             // = skd

    @Column(name = "coupon_id", nullable = false)
    private Long couponId;                 // id coupon SUMBER (259)

    @Column(name = "transaction_id")
    private String transactionId;          // id transaksi pihak ketiga

    private String sku;
    private String couponName;             // nama campaign
    private Double discPercentage;
    private String scanType;               // type_scan, "QR"
    private String tag;                    // "sarinah-injourney"
    private String state;                  // dari skd_line.state

    @Column(name = "is_used", nullable = false)
    private Boolean isUsed = false;

    private LocalDateTime startDate;
    private LocalDateTime endDate;

    @Column(name = "redeem_date")
    private LocalDateTime redeemDate;      // kapan kupon ditebus (null bila belum)

    @Column(columnDefinition = "TEXT")
    private String termsText;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CouponStatus status;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String rawJson;

    private Instant createdAt;
}
