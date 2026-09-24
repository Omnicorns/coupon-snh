package com.sarinah.coupon.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.sarinah.coupon.dto.SyncIsUsedResponse;
import com.sarinah.coupon.entity.CouponStatus;
import com.sarinah.coupon.entity.GeneratedCoupon;
import com.sarinah.coupon.repository.GeneratedCouponRepository;
import com.sarinah.coupon.webhook.CouponWebhookPublisher;
import com.sarinah.coupon.webhook.RedemptionDetail;
import com.sarinah.coupon.webhook.WebhookEventType;
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
    private final RedemptionDetailResolver redemptionDetailResolver;  // memakai PostOrderHistoryService
    private final CouponWebhookPublisher webhookPublisher;

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

            PortalTemplate tpl;
            try {
                tpl = fetchPortalTemplate(sku);
            } catch (Exception e) {
                log.error("Sync is_used gagal untuk sku={}: {}", sku, e.getMessage());
                failedSkus.add(sku);
                continue;
            }
            if (tpl == null) {
                failedSkus.add(sku);
                continue;
            }

            // 4) cocokkan tiap kupon dengan line portal-nya (error per kupon tidak menghentikan kupon lain)
            for (GeneratedCoupon c : entry.getValue()) {
                try {
                    if (applyPortalState(c, tpl.lineBySkd().get(c.getCouponCode()), tpl)) {
                        updated++;
                    }
                } catch (Exception e) {
                    log.error("Sync: gagal proses code={} (sku={}): {}", c.getCouponCode(), sku, e.getMessage(), e);
                }
            }
        }

        log.info("Sync is_used selesai: dicek={}, terupdate={}, skuGagal={}",
                pending.size(), updated, failedSkus);
        return new SyncIsUsedResponse(pending.size(), updated, failedSkus);
    }

    /** Data template kupon dari portal: id (= couponId PRIME), sku, name, dan skd_line per kode. */
    private record PortalTemplate(String id, String sku, String name, Map<String, JsonNode> lineBySkd) {
    }

    /** 3) ambil template + skd_line portal untuk satu sku. Null = portal kosong. */
    private PortalTemplate fetchPortalTemplate(String sku) {
        ObjectNode portalReq = JsonNodeFactory.instance.objectNode();
        portalReq.put("sku", sku);
        ArrayNode result = postCouponListService.execute(portalReq);
        if (result == null || result.isEmpty()) {
            log.warn("Sync is_used: portal tidak mengembalikan data untuk sku={}", sku);
            return null;
        }
        JsonNode node = result.get(0);
        Map<String, JsonNode> lineBySkd = new HashMap<>();
        for (JsonNode line : node.path("skd_line")) {
            String skd = textOrNull(line, "skd");
            if (skd != null) lineBySkd.put(skd, line);
        }
        String templateId = textOrNull(node, "id");                 // 259
        String portalSku = textOrDefault(node, "sku", sku);        // DCDWP00220
        String name = textOrNull(node, "name");                    // Diskon 10% Injourney Besties
        if (templateId == null) {
            log.warn("Sync: template sku={} tanpa field id, couponId webhook kosong", sku);
        }
        return new PortalTemplate(templateId, portalSku, name, lineBySkd);
    }

    /** @return true bila status kupon berubah (disimpan + event masuk antrean). */
    private boolean applyPortalState(GeneratedCoupon c, JsonNode line, PortalTemplate tpl) {

        // Aturan 3: skd tidak ada lagi di skd_line portal -> REVOKED -> event coupon.revoked
        if (line == null) {
            if (c.getStatus() == CouponStatus.REVOKED) return false;   // sudah dikirim sebelumnya
            c.setStatus(CouponStatus.REVOKED);
            webhookPublisher.saveAndPublish(c, WebhookEventType.REVOKED, tpl.id(), LocalDateTime.now(ZONE));
            log.warn("Sync: code={} tidak ada di skd_line portal (sku={}) -> REVOKED", c.getCouponCode(), tpl.sku());
            return true;
        }

        // Aturan 1: sudah dipakai -> REDEEMED (menang atas expired) -> event coupon.redeemed
        if (line.path("is_used").asBoolean(false)) {
            String receiptNumber = textOrNull(line, "order_id_char");   // "Order 59511-006-0012"
            LocalDateTime redeemDate = parseRedeemDate(textOrNull(line, "write_date"));

            // Panggil order history DI LUAR transaksi DB; gagal pun tetap lanjut (field opsional)
            RedemptionDetail detail = redemptionDetailResolver.resolve(
                    receiptNumber, redeemDate, tpl.sku(), tpl.name());

            c.setIsUsed(true);
            c.setState(textOrDefault(line, "state", "used"));
            c.setRedeemDate(redeemDate);
            c.setStatus(CouponStatus.REDEEMED);
            webhookPublisher.saveAndPublish(c, WebhookEventType.REDEEMED, tpl.id(), redeemDate, detail);

            log.info("Sync: code={} -> REDEEMED (write_date={}, order={}, store={}, discount={})",
                    c.getCouponCode(), textOrNull(line, "write_date"),
                    detail.transactionId(), detail.storeId(), detail.discount());
            return true;
        }

        // Aturan 2: belum dipakai tapi tanggal lewat end -> EXPIRED -> event coupon.expired
        if (isExpired(c)) {
            if (c.getStatus() == CouponStatus.EXPIRED) return false;   // sudah dikirim sebelumnya
            c.setStatus(CouponStatus.EXPIRED);
            webhookPublisher.saveAndPublish(c, WebhookEventType.EXPIRED, tpl.id(), c.getEndDate());
            log.info("Sync: code={} -> EXPIRED (endDate={})", c.getCouponCode(), c.getEndDate());
            return true;
        }

        // selain itu: masih ACTIVE, biarkan
        return false;
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