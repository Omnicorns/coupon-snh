package com.sarinah.coupon.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.sarinah.coupon.dto.SyncIsUsedResponse;
import com.sarinah.coupon.exception.PortalResponseException;
import com.sarinah.coupon.util.PortalTime;
import com.sarinah.coupon.entity.CouponStatus;
import com.sarinah.coupon.entity.GeneratedCoupon;
import com.sarinah.coupon.repository.GeneratedCouponRepository;
import com.sarinah.coupon.webhook.CouponWebhookPublisher;
import com.sarinah.coupon.webhook.RedemptionDetail;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
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
    // write_date portal adalah string tanpa zona; zona ditentukan PortalTime

    private final GeneratedCouponRepository generatedCouponRepository;
    private final PostCouponListService postCouponListService;
    private final RedemptionDetailResolver redemptionDetailResolver;  // memakai PostOrderHistoryService
    private final CouponWebhookPublisher webhookPublisher;
    private final PortalTime time;

    public SyncIsUsedResponse syncIsUsed() {
        // 1) hanya yang belum used
        List<GeneratedCoupon> pending = generatedCouponRepository.findByIsUsedFalse();
        if (pending.isEmpty()) {
            log.info("Sync is_used: tidak ada kupon pending");
            return new SyncIsUsedResponse(0, 0, List.of());
        }

        // 2) group by sku -> 1 panggilan portal per sku (kupon tanpa sku tidak bisa dicek)
        Map<String, List<GeneratedCoupon>> bySku = pending.stream()
                .filter(c -> c.getSku() != null)
                .collect(Collectors.groupingBy(GeneratedCoupon::getSku));

        int updated = 0;
        List<String> failedSkus = new ArrayList<>();

        for (Map.Entry<String, List<GeneratedCoupon>> entry : bySku.entrySet()) {
            String sku = entry.getKey();

            // 3) Respons yang tidak bisa dipercaya → lewati SKU ini, JANGAN ubah status apa pun
            Map<String, PortalTemplate> templates;
            try {
                templates = fetchPortalTemplates(sku);
            } catch (Exception e) {
                log.error("Sync is_used gagal untuk sku={}, tidak ada status yang diubah: {}", sku, e.getMessage());
                failedSkus.add(sku);
                continue;
            }

            // 4) cocokkan tiap kupon dengan template campaign-nya (error per kupon tidak menghentikan kupon lain)
            for (GeneratedCoupon c : entry.getValue()) {
                PortalTemplate tpl = templates.get(String.valueOf(c.getCouponId()));
                if (tpl == null) {
                    log.warn("Sync: template campaign {} (sku={}) tidak ada di respons portal, code={} dilewati",
                            c.getCouponId(), sku, c.getCouponCode());
                    continue;
                }
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

    /**
     * Template portal untuk satu sku, dikunci per id campaign. Melempar exception (→ SKU dilewati)
     * bila respons tidak lengkap, supaya respons error/kosong tidak dibaca sebagai "semua kode hilang".
     */
    private Map<String, PortalTemplate> fetchPortalTemplates(String sku) {
        ObjectNode portalReq = JsonNodeFactory.instance.objectNode();
        portalReq.put("sku", sku);
        ArrayNode result = postCouponListService.execute(portalReq);   // non-2xx / non-JSON sudah dilempar adaptor
        if (result == null || result.isEmpty()) {
            throw new PortalResponseException("portal tidak mengembalikan template untuk sku=" + sku);
        }

        Map<String, PortalTemplate> byId = new HashMap<>();
        for (JsonNode node : result) {
            String portalSku = textOrNull(node, "sku");
            if (!sku.equals(portalSku)) continue;                      // identitas SKU wajib cocok
            String templateId = textOrNull(node, "id");                 // 259
            if (templateId == null) {
                throw new PortalResponseException("template sku=" + sku + " tanpa id");
            }
            JsonNode lines = node.path("skd_line");
            if (!lines.isArray()) {
                throw new PortalResponseException("template " + templateId + " tanpa skd_line");
            }
            if (lines.isEmpty()) {
                // Kode yang sudah kita terbitkan pasti pernah ada di skd_line; daftar kosong total
                // lebih mungkin respons tidak lengkap daripada semua kode dicabut.
                throw new PortalResponseException("skd_line template " + templateId + " kosong");
            }
            Map<String, JsonNode> lineBySkd = new HashMap<>();
            for (JsonNode line : lines) {
                String skd = textOrNull(line, "skd");
                if (skd != null) lineBySkd.put(skd, line);
            }
            if (lineBySkd.isEmpty()) {
                throw new PortalResponseException("skd_line template " + templateId + " tanpa field skd");
            }
            byId.put(templateId, new PortalTemplate(templateId, portalSku, textOrNull(node, "name"), lineBySkd));
        }
        if (byId.isEmpty()) {
            throw new PortalResponseException("tidak ada template dengan sku=" + sku + " di respons portal");
        }
        return byId;
    }

    /** @return true bila status kupon berubah (disimpan + event masuk antrean). */
    private boolean applyPortalState(GeneratedCoupon c, JsonNode line, PortalTemplate tpl) {

        // Aturan 3: skd tidak ada lagi di skd_line portal (yang sudah tervalidasi lengkap) -> REVOKED
        if (line == null) {
            if (c.getStatus() == CouponStatus.REVOKED) return false;   // sudah dikirim sebelumnya
            boolean changed = webhookPublisher.markRevoked(c, tpl.id());
            if (changed) {
                log.warn("Sync: code={} tidak ada di skd_line portal (sku={}) -> REVOKED", c.getCouponCode(), tpl.sku());
            }
            return changed;
        }

        // Aturan 1: sudah dipakai -> REDEEMED (menang atas expired) -> event coupon.redeemed
        if (line.path("is_used").asBoolean(false)) {
            String receiptNumber = textOrNull(line, "order_id_char");   // "Order 59511-006-0012"
            LocalDateTime redeemDate = parseRedeemDate(textOrNull(line, "write_date"));

            // Panggil order history DI LUAR transaksi DB; gagal pun tetap lanjut (field opsional)
            RedemptionDetail detail = redemptionDetailResolver.resolve(
                    receiptNumber, redeemDate, tpl.sku(), tpl.name());

            boolean changed = webhookPublisher.markRedeemed(
                    c, textOrDefault(line, "state", "used"), redeemDate, tpl.id(), detail);
            if (changed) {
                log.info("Sync: code={} -> REDEEMED (write_date={}, order={}, store={}, discount={})",
                        c.getCouponCode(), textOrNull(line, "write_date"),
                        detail.transactionId(), detail.storeId(), detail.discount());
            }
            return changed;
        }

        // Aturan 2: belum dipakai tapi tanggal lewat end -> EXPIRED -> event coupon.expired
        if (isExpired(c)) {
            if (c.getStatus() != CouponStatus.ACTIVE) return false;    // sudah EXPIRED / REVOKED
            boolean changed = webhookPublisher.markExpired(c, tpl.id());
            if (changed) {
                log.info("Sync: code={} -> EXPIRED (endDate={})", c.getCouponCode(), c.getEndDate());
            }
            return changed;
        }

        // selain itu: masih ACTIVE, biarkan
        return false;
    }

    private LocalDateTime parseRedeemDate(String value) {
        if (value == null || value.isBlank()) return time.now();
        try {
            return LocalDateTime.parse(value, SRC);   // "yyyy-MM-dd HH:mm:ss"
        } catch (Exception e) {
            return time.now();
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
        return c.getEndDate() != null && c.getEndDate().isBefore(time.now());
    }
}