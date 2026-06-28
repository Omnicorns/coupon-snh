package com.sarinah.coupon.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Representasi kupon di database kita (hasil sync dari portal Sarinah/Odoo).
 * Primary key memakai id asli portal → saveAll() bersifat upsert (tidak menggandakan).
 */
@Entity
@Table(name = "coupons")
@Getter
@Setter
@NoArgsConstructor
public class Coupon {

    @Id
    private Long id;                       // "id": 259

    // --- identitas ---
    private String sku;                    // "DCDWP00220"
    private String name;                   // "Diskon 10% Injourney Besties"
    private String displayName;            // dari display_name

    @Column(columnDefinition = "TEXT")
    private String description;            // "-"
    private String skdCode;                // "2026062272196"

    // --- nilai diskon ---
    private Double discPercentage;         // 10.0
    private Long maxDiscount;              // 1000000
    private Long minimumTransaction;       // 500000
    private Double minAmountShow;          // 0.0
    private Double maxAmountShow;          // 9999999.0

    // --- aturan & flag ---
    private String typeScan;               // "QR"
    private Boolean active;                // true
    private Boolean isInjourney;           // true
    private Boolean onlyNormalPrice;       // true
    private Boolean availableInReceipt;    // false
    private Boolean availableSkdLine;      // true

    // --- sharing promo ---
    private Boolean isSharingPromotion;    // false
    private Double vendorShared;           // 0.0
    private Double sarinahShared;          // 100.0
    private Boolean isAllBrand;            // false

    // --- periode (LocalDateTime → bisa di-query rentang waktu) ---
    private LocalDateTime startDate;       // 2026-01-08 17:55:55
    private LocalDateTime endDate;         // 2026-12-31 16:55:55

    // --- audit dari portal ---
    private String createUid;              // "Ledies Clara Simanjuntak"
    private LocalDateTime createDate;
    private String writeUid;               // "Public user"
    private LocalDateTime writeDate;

    // --- hari berlaku (dari day_of_week_ids) ---
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "coupon_days", joinColumns = @JoinColumn(name = "coupon_id"))
    @Column(name = "day_name")
    private List<String> days = new ArrayList<>();

    // --- jejak ---
    @Lob
    @Column(columnDefinition = "TEXT")
    private String rawJson;                // dokumen portal utuh (cadangan)

    private Instant syncedAt;              // kapan terakhir disinkron
}
