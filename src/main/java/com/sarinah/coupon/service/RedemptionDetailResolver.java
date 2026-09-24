package com.sarinah.coupon.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sarinah.coupon.webhook.CouponWebhookPayload.DiscountApplied;
import com.sarinah.coupon.webhook.PrimeWebhookProperties;
import com.sarinah.coupon.webhook.RedemptionDetail;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.time.ZoneId;

/**
 * Ambil detail transaksi dari order history POS untuk event coupon.redeemed:
 *   storeId         <- order.location          ("THA/Stock/Thamrin")
 *   transactionId   <- order.order_ref         ("JKTTHM-DL303/260919367")
 *   discountApplied <- baris order_lines minus milik kupon ini ({"currency":"IDR","value":"95900"})
 *
 * Tidak pernah melempar exception: kalau order history gagal, webhook tetap jalan
 * dengan field opsional yang kosong.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedemptionDetailResolver {

    private static final String CURRENCY = "IDR";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE; // yyyy-MM-dd

    private final PostOrderHistoryService postOrderHistoryService;
    private final PrimeWebhookProperties props;

    private static final ZoneId ZONE = ZoneId.of("Asia/Jakarta");

    /**
     * @param receiptNumber skd_line.order_id_char, mis. "Order 59511-006-0012"
     * @param redeemedAt    skd_line.write_date (untuk date_from / date_to)
     * @param sku           sku template kupon dari portal, mis. "DCDWP00220"
     * @param couponName    name template kupon dari portal, mis. "Diskon 10% Injourney Besties"
     */
    public RedemptionDetail resolve(String receiptNumber, LocalDateTime redeemedAt,
                                    String sku, String couponName) {
        if (receiptNumber == null || receiptNumber.isBlank()) {
            return RedemptionDetail.EMPTY;
        }
        RedemptionDetail fallback = new RedemptionDetail(null, receiptNumber, null);
        try {
            JsonNode order = findOrder(receiptNumber, redeemedAt);
            if (order == null) {
                log.warn("Order history: receipt '{}' tidak ditemukan, kirim tanpa storeId/discount", receiptNumber);
                return fallback;
            }
            String storeId = textOrNull(order, "location");
            String trxId = textOrNull(order, "order_ref");
            DiscountApplied discount = findCouponDiscount(order.path("order_lines"), sku, couponName, receiptNumber);

            return new RedemptionDetail(storeId, trxId != null ? trxId : receiptNumber, discount);

        } catch (Exception e) {
            log.warn("Gagal ambil order history receipt='{}': {}", receiptNumber, e.getMessage());
            return fallback;
        }
    }

    /**
     * 1) cari di rentang tanggal redeem +/- N hari (cepat, hasil sedikit);
     * 2) kalau tidak ketemu, ulangi tanpa filter tanggal (date_from/date_to kosong).
     */
//    private JsonNode findOrder(String receiptNumber, LocalDateTime redeemedAt) {
//        if (redeemedAt != null) {
//            LocalDate day = redeemedAt.toLocalDate();
//            int n = Math.max(0, props.getOrderLookupDays());
//            JsonNode order = query(receiptNumber, day.minusDays(n).format(DATE), day.plusDays(n).format(DATE));
//            if (order != null) return order;
//            log.debug("Order history: receipt '{}' tidak ada di {}±{} hari, coba tanpa filter tanggal",
//                    receiptNumber, day, n);
//        }
//        return query(receiptNumber, "", "");
//    }

    // SESUDAH
    private JsonNode findOrder(String receiptNumber, LocalDateTime redeemedAt) {
        LocalDate day = redeemedAt != null ? redeemedAt.toLocalDate() : LocalDate.now(ZONE);

        int n = Math.max(0, props.getOrderLookupDays());
        JsonNode order = queryAround(receiptNumber, day, n);
        if (order != null) return order;

        int wide = props.getOrderLookupFallbackDays();
        if (wide > n) {
            log.debug("Order history: receipt '{}' tidak ada di {}±{} hari, coba ±{} hari",
                    receiptNumber, day, n, wide);
            return queryAround(receiptNumber, day, wide);
        }
        return null;
    }

    private JsonNode queryAround(String receiptNumber, LocalDate day, int days) {
        LocalDate from = day.minusDays(days);
        LocalDate to = day.plusDays(days);
        LocalDate today = LocalDate.now(ZONE);
        if (to.isAfter(today)) to = today;
        if (from.isAfter(to)) from = to;
        return query(receiptNumber, from.format(DATE), to.format(DATE));
    }

    private JsonNode query(String receiptNumber, String dateFrom, String dateTo) {
        ObjectNode req = JsonNodeFactory.instance.objectNode();
        req.put("date_from", dateFrom);
        req.put("date_to", dateTo);
        req.put("receipt_number", receiptNumber);
        ArrayNode data = postOrderHistoryService.execute(req);
        if (data == null || data.isEmpty()) return null;

        // Hanya terima order yang receipt_number-nya persis sama; jangan ambil order lain.
        for (JsonNode o : data) {
            if (receiptNumber.equals(textOrNull(o, "receipt_number"))) return o;
        }
        log.warn("Order history: {} order dikembalikan, tidak ada receipt_number = '{}'", data.size(), receiptNumber);
        return null;
    }

    /**
     * Cari baris diskon kupon di order_lines (baris bernilai minus), berurutan:
     *   1) product diawali kode sku, mis. "[DCDWP00220] ..."   -> paling pasti
     *   2) product mengandung name template (tidak case-sensitive;
     *      portal "Injourney" vs POS "InJourney")
     *   3) hanya ada satu baris minus -> pakai itu
     *   selain itu -> null (lebih baik tidak dikirim daripada salah nominal)
     */
    private DiscountApplied findCouponDiscount(JsonNode orderLines, String sku, String couponName, String ref) {
        List<JsonNode> negatives = new ArrayList<>();
        for (JsonNode line : orderLines) {            // order_lines berupa object {id: line}
            if (lineAmount(line).signum() < 0) negatives.add(line);
        }
        if (negatives.isEmpty()) {
            log.warn("Order {} tidak punya baris diskon minus", ref);
            return null;
        }

        String skuTag = sku != null && !sku.isBlank() ? "[" + normalize(sku) + "]" : null;
        String name = couponName != null && !couponName.isBlank() ? normalize(couponName) : null;

        BigDecimal bySku = BigDecimal.ZERO, byName = BigDecimal.ZERO;
        boolean skuFound = false, nameFound = false;

        for (JsonNode line : negatives) {
            String product = normalize(textOrNull(line, "product"));
            if (skuTag != null && product.contains(skuTag)) {
                bySku = bySku.add(lineAmount(line));
                skuFound = true;
            } else if (name != null && product.contains(name)) {
                byName = byName.add(lineAmount(line));
                nameFound = true;
            }
        }

        BigDecimal amount;
        if (skuFound) {
            amount = bySku;
        } else if (nameFound) {
            amount = byName;
        } else if (negatives.size() == 1) {
            log.warn("Order {}: baris diskon tidak cocok sku/name, pakai satu-satunya baris minus", ref);
            amount = lineAmount(negatives.get(0));
        } else {
            log.warn("Order {}: {} baris minus, tidak ada yang cocok sku '{}' / name '{}', discount tidak dikirim",
                    ref, negatives.size(), sku, couponName);
            return null;
        }
        // -95900.0 -> "95900"
        return new DiscountApplied(CURRENCY, amount.abs().stripTrailingZeros().toPlainString());
    }

    /** Nominal baris: price_subtotal_w/o_tax, fallback price_unit * qty. */
    private BigDecimal lineAmount(JsonNode line) {
        JsonNode sub = line.path("price_subtotal_w/o_tax");
        if (sub.isNumber()) return sub.decimalValue();
        JsonNode unit = line.path("price_unit");
        if (!unit.isNumber()) return BigDecimal.ZERO;
        BigDecimal qty = line.path("qty").isNumber() ? line.path("qty").decimalValue() : BigDecimal.ONE;
        return unit.decimalValue().multiply(qty);
    }

    /** lowercase + rapikan spasi, supaya perbandingan tidak peka huruf besar / spasi ganda. */
    private static String normalize(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    /** false/null/missing (khas Odoo) -> null. */
    private static String textOrNull(JsonNode n, String f) {
        JsonNode v = n.path(f);
        if (v.isMissingNode() || v.isNull()) return null;
        if (v.isBoolean() && !v.asBoolean()) return null;
        String s = v.asText();
        return s.isBlank() ? null : s;
    }
}

