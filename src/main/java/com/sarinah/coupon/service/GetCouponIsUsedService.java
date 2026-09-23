package com.sarinah.coupon.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.sarinah.coupon.dto.SyncIsUsedResponse;
import com.sarinah.coupon.entity.CouponStatus;
import com.sarinah.coupon.entity.GeneratedCoupon;
import com.sarinah.coupon.repository.GeneratedCouponRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;


import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class GetCouponIsUsedService {

    private static final DateTimeFormatter SRC = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    // write_date portal adalah string tanpa zona; samakan dengan zona portal
    private static final ZoneId ZONE = ZoneId.of("Asia/Jakarta");

    private final GeneratedCouponRepository generatedCouponRepository;
    private final PostCouponListService postCouponListService;

    public SyncIsUsedResponse syncIsUsed() {
        // 1) hanya yang belum used
        List<GeneratedCoupon> pending = generatedCouponRepository.findByIsUsedFalse();
        if (pending.isEmpty()) {
            log.info("Sync is_used: tidak ada kupon pending");
            return new SyncIsUsedResponse(0, 0, List.of());
        }

        // 2) group by sku -> 1 panggilan portal per sku
        Map<String, List<GeneratedCoupon>> bySku = pending.stream()
                .collect(Collectors.groupingBy(GeneratedCoupon::getSku));

        int updated = 0;
        List<String> failedSkus = new ArrayList<>();

        for (Map.Entry<String, List<GeneratedCoupon>> entry : bySku.entrySet()) {
            String sku = entry.getKey();
            try {
                ObjectNode portalReq = JsonNodeFactory.instance.objectNode();
                portalReq.put("sku", sku);
                ArrayNode result = postCouponListService.execute(portalReq);
                if (result == null || result.isEmpty()) {
                    log.warn("Sync is_used: portal tidak mengembalikan data untuk sku={}", sku);
                    failedSkus.add(sku);
                    continue;
                }
                JsonNode node = result.get(0);

                // 3) index skd_line by kode skd
                Map<String, JsonNode> lineBySkd = new HashMap<>();
                for (JsonNode line : node.path("skd_line")) {
                    String skd = textOrNull(line, "skd");
                    if (skd != null) lineBySkd.put(skd, line);
                }

                // 4) cocokkan tiap kupon kita dengan line portal-nya
                // 4) cocokkan tiap kupon kita dengan line portal-nya
                for (GeneratedCoupon c : entry.getValue()) {
                    JsonNode line = lineBySkd.get(c.getCouponCode());

                    // Aturan 3: skd tidak ada lagi di skd_line portal → EXPIRED
                    if (line == null) {
                        log.warn("Sync: code={} tidak ada di skd_line portal (sku={}) → EXPIRED",
                                c.getCouponCode(), sku);
                        c.setStatus(CouponStatus.EXPIRED);
                        generatedCouponRepository.save(c);
                        updated++;
                        continue;
                    }

                    boolean portalUsed = line.path("is_used").asBoolean(false);

                    if (portalUsed) {
                        // Aturan 1: sudah dipakai → REDEEMED (menang atas expired)
                        c.setIsUsed(true);
                        c.setState(textOrDefault(line, "state", "used"));
                        c.setRedeemDate(parseRedeemDate(textOrNull(line, "write_date")));
                        c.setStatus(CouponStatus.REDEEMED);
                        generatedCouponRepository.save(c);
                        updated++;

                        log.info("Sync: code={} → REDEEMED (write_date={})",
                                c.getCouponCode(), textOrNull(line, "write_date"));

                    } else if (isExpired(c)) {
                        // Aturan 2: belum dipakai tapi tanggal lewat end → EXPIRED
                        c.setStatus(CouponStatus.EXPIRED);
                        generatedCouponRepository.save(c);
                        updated++;

                        log.info("Sync: code={} → EXPIRED (endDate={})", c.getCouponCode(), c.getEndDate());
                    }
                    // selain itu: masih ACTIVE, biarkan

                }
            } catch (Exception e) {
                log.error("Sync is_used gagal untuk sku={}: {}", sku, e.getMessage());
                failedSkus.add(sku);
            }
        }

        log.info("Sync is_used selesai: dicek={}, terupdate={}, skuGagal={}",
                pending.size(), updated, failedSkus);
        return new SyncIsUsedResponse(pending.size(), updated, failedSkus);
    }


    private LocalDateTime parseRedeemDate(String value) {
        if (value == null || value.isBlank()) return LocalDateTime.now(ZONE);
        try {
            return LocalDateTime.parse(value, SRC);   // "yyyy-MM-dd HH:mm:ss"
        } catch (Exception e) {
            return LocalDateTime.now(ZONE);
        }
    }

    /** Ambil field text; false/null/missing (khas Odoo) -> null. */
    private String textOrNull(JsonNode n, String f) {
        JsonNode v = n.path(f);
        if (v.isMissingNode() || v.isNull()) return null;
        if (v.isBoolean() && !v.asBoolean()) return null;
        return v.asText();
    }

    private String textOrDefault(JsonNode n, String f, String def) {
        String v = textOrNull(n, f);
        return v != null ? v : def;
    }

    private boolean isExpired(GeneratedCoupon c) {
        return c.getEndDate() != null && c.getEndDate().isBefore(LocalDateTime.now(ZONE));
    }







}

