package com.sarinah.coupon.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sarinah.coupon.entity.Coupon;
import com.sarinah.coupon.repository.CouponRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Jalur TULIS: tarik data dari portal lalu simpan ke DB kita.
 * Pemetaan JSON portal → entity terjadi di sini, dengan helper yang tahan
 * terhadap kebiasaan Odoo mengisi field kosong dengan nilai boolean `false`.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CouponSyncService {

    private static final DateTimeFormatter ODOO_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final PostCouponListService postCouponListService;
    private final CouponRepository repo;

    @Transactional
    public void sync(ObjectNode request) {
        ArrayNode list = postCouponListService.execute(request);

        List<Coupon> toSave = new ArrayList<>();
        for (JsonNode node : list) {
            toSave.add(map(node));
        }
        repo.saveAll(toSave);                 // PK = id portal → upsert
        log.info("Sync coupon selesai: {} record", toSave.size());
    }

    private Coupon map(JsonNode n) {
        Coupon c = new Coupon();
        c.setId(n.path("id").asLong());

        c.setSku(text(n, "sku"));
        c.setName(text(n, "name"));
        c.setDisplayName(text(n, "display_name"));
        c.setDescription(text(n, "description"));
        c.setSkdCode(text(n, "skd_code"));

        c.setDiscPercentage(dbl(n, "disc_percentage"));
        c.setMaxDiscount(lng(n, "max_discount"));
        c.setMinimumTransaction(lng(n, "minimum_transaction"));
        c.setMinAmountShow(dbl(n, "min_amount_show"));
        c.setMaxAmountShow(dbl(n, "max_amount_show"));

        c.setTypeScan(text(n, "type_scan"));
        c.setActive(bool(n, "active"));
        c.setIsInjourney(bool(n, "is_injourney"));
        c.setOnlyNormalPrice(bool(n, "only_normal_price"));
        c.setAvailableInReceipt(bool(n, "available_in_receipt"));
        c.setAvailableSkdLine(bool(n, "available_skd_line"));

        c.setIsSharingPromotion(bool(n, "is_sharing_promotion"));
        c.setVendorShared(dbl(n, "vendor_shared"));
        c.setSarinahShared(dbl(n, "sarinah_shared"));
        c.setIsAllBrand(bool(n, "is_all_brand"));

        c.setStartDate(dateTime(n, "start_date"));
        c.setEndDate(dateTime(n, "end_date"));
        c.setCreateUid(text(n, "create_uid"));
        c.setCreateDate(dateTime(n, "create_date"));
        c.setWriteUid(text(n, "write_uid"));
        c.setWriteDate(dateTime(n, "write_date"));

        List<String> days = new ArrayList<>();
        for (JsonNode d : n.path("day_of_week_ids")) {   // bukan "days"
            days.add(d.path("name").asText());
        }
        c.setDays(days);

        c.setRawJson(n.toString());
        c.setSyncedAt(Instant.now());
        return c;
    }

    // ---- helper: Odoo isi field kosong dengan `false` ----

    private String text(JsonNode n, String f) {
        JsonNode v = n.path(f);
        if (v.isMissingNode() || v.isNull()) return null;
        if (v.isBoolean() && !v.asBoolean()) return null;   // false = kosong
        return v.asText();
    }

    private Boolean bool(JsonNode n, String f) {
        JsonNode v = n.path(f);
        return v.isBoolean() ? v.asBoolean() : null;
    }

    private Double dbl(JsonNode n, String f) {
        JsonNode v = n.path(f);
        return v.isNumber() ? v.asDouble() : null;
    }

    private Long lng(JsonNode n, String f) {
        JsonNode v = n.path(f);
        return v.isNumber() ? v.asLong() : null;
    }

    private LocalDateTime dateTime(JsonNode n, String f) {
        String s = text(n, f);
        if (s == null || s.isBlank()) return null;
        try {
            return LocalDateTime.parse(s, ODOO_FMT);
        } catch (Exception e) {
            log.debug("Gagal parse tanggal '{}' di field {}", s, f);
            return null;
        }
    }
}
