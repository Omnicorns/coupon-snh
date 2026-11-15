package com.sarinah.coupon.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sarinah.coupon.dto.GenerateCouponRequest;
import com.sarinah.coupon.dto.GenerateCouponResponse;
import com.sarinah.coupon.entity.Coupon;
import com.sarinah.coupon.entity.CouponStatus;
import com.sarinah.coupon.entity.GeneratedCoupon;
import com.sarinah.coupon.exception.ConflictException;
import com.sarinah.coupon.exception.IdempotencyKeyReusedException;
import com.sarinah.coupon.exception.PortalResponseException;
import com.sarinah.coupon.exception.CouponExhaustedException;
import com.sarinah.coupon.exception.CouponNotFoundException;
import com.sarinah.coupon.exception.InvalidRequestException;
import com.sarinah.coupon.repository.CouponRepository;
import com.sarinah.coupon.repository.GeneratedCouponRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;

/**
 * Menerbitkan kode kupon (= skd dari portal) untuk sebuah coupon sumber.
 * idempotencyKey diklaim secara atomik (tabel coupon_generate_requests) sehingga retry maupun
 * request bersamaan dengan key yang sama tidak memanggil portal / mencetak kode ganda.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CouponGenerateService {

    private final GeneratedCouponRepository generatedRepo;
    private final CouponRepository couponRepo;
    private final PostCouponListService postCouponListService;
    private final GeneratedCouponMapper mapper;
    private final GenerateIdempotencyService idempotency;
    private final TransactionTemplate tx;
    private final CampaignEligibility eligibility;
    private final CouponSyncService couponSyncService;

    /**
     * Alur idempotency:
     * 1) Klaim key secara atomik (commit sebelum portal dipanggil) → hanya satu request yang lanjut.
     * 2) Pemilik klaim memanggil portal, lalu menyimpan kupon + menandai klaim COMPLETED dalam
     *    SATU transaksi.
     * 3) Bila gagal sebelum tersimpan, klaim dilepas sebagai FAILED sehingga retry dengan key
     *    yang sama bisa mencoba lagi. Request yang datang saat klaim masih PROCESSING mendapat 409.
     *
     * Method ini sengaja TIDAK @Transactional: koneksi DB tidak ditahan selama panggilan portal.
     */
    public GenerateCouponResponse generate(String partnerId, GenerateCouponRequest req) {
        if (partnerId == null || partnerId.isBlank()) {
            throw new IllegalStateException("partnerId dari autentikasi wajib ada");
        }
        validate(req);
        String key = req.idempotencyKey();

        // 1) Klaim atomik
        GenerateIdempotencyService.Claim claim = idempotency.acquire(partnerId, req);
        if (!claim.owned()) {
            return generatedRepo.findById(claim.completedCouponId())
                    .map(mapper::toResponse)
                    .orElseThrow(() -> new IllegalStateException(
                            "Klaim COMPLETED tanpa kupon, key=" + key));
        }
        String token = claim.token();

        try {
            // Data lama (sebelum tabel klaim ada) mungkin sudah punya kupon dengan key ini.
            var legacy = generatedRepo.findByIdempotencyKey(key);
            if (legacy.isPresent()) {
                if (!partnerId.equals(legacy.get().getPartnerId())) {
                    // Kupon lama milik partner lain / belum di-backfill → jangan pernah dikembalikan.
                    throw new IdempotencyKeyReusedException(key);
                }
                tx.executeWithoutResult(s -> idempotency.complete(key, token, legacy.get().getId()));
                log.info("Idempotent hit (data lama) key={}", key);
                return mapper.toResponse(legacy.get());
            }

            GeneratedCoupon coupon = issue(partnerId, req);

            // 4) Simpan kupon + tandai klaim COMPLETED secara atomik
            tx.executeWithoutResult(s -> {
                generatedRepo.saveAndFlush(coupon);
                if (!idempotency.complete(key, token, coupon.getId())) {
                    // Lease habis dan klaim diambil alih request lain → batalkan simpanan kita
                    throw new ConflictException("Kupon sedang diterbitkan request lain, silakan ulangi");
                }
            });

            log.info("Kupon terbit: id={}, code={}, dari couponId={}, tag={}",
                    coupon.getId(), coupon.getCouponCode(), req.couponId(), coupon.getTag());
            return mapper.toResponse(coupon);

        } catch (DataIntegrityViolationException e) {
            // Kode skd yang sama baru saja diambil request lain (key berbeda) → lepas klaim, minta retry.
            log.warn("Konflik penyimpanan key={}: {}", key, e.getMostSpecificCause().getMessage());
            idempotency.release(key, token, e);
            throw new ConflictException("Kupon sedang diterbitkan request lain, silakan ulangi");
        } catch (RuntimeException e) {
            idempotency.release(key, token, e);
            throw e;
        }
    }

    /** Panggil portal dan susun entity kupon (belum disimpan). */
    private GeneratedCoupon issue(String partnerId, GenerateCouponRequest req) {
        // 2) Coupon sumber harus ada (butuh sku untuk memanggil portal)
        Coupon source = couponRepo.findById(req.couponId())
                .orElseThrow(() -> new CouponNotFoundException(req.couponId()));

        // 3) Panggil portal → mencetak skd baru
        ObjectNode portalReq = JsonNodeFactory.instance.objectNode();
        portalReq.put("sku", source.getSku());
        ArrayNode result = postCouponListService.execute(portalReq);
        if (result.isEmpty()) {
            throw new InvalidRequestException(
                    "Portal tidak mengembalikan data untuk coupon " + req.couponId());
        }
        JsonNode node = matchingTemplate(result, source);

        // Kelayakan dinilai dari template portal TERBARU (active, periode, hari), bukan dari hasil
        // sync lokal yang bisa basi — mis. masa berlaku dipersingkat di portal sejak sync terakhir.
        Coupon campaign = currentCampaign(source, node);
        eligibility.check(campaign);

        // Kode kupon = `skd` dari salah satu skd_line yang BELUM dipakai DAN belum kita tag.
        // skd_code (top-level) BUKAN kode kupon — hanya penanda.
        JsonNode line = pickAvailableLine(node);
        if (line == null) {
            log.info("Semua skd untuk coupon {} sudah dipakai/ter-tag → habis", req.couponId());
            throw new CouponExhaustedException(req.couponId(), "all");
        }
        String code = text(line, "skd");

        String tag = (req.tag() != null && !req.tag().isBlank())
                ? req.tag()
                : (Boolean.TRUE.equals(campaign.getIsInjourney()) ? "sarinah-injourney" : "sarinah");

        // Ringkasan campaign ikut, supaya response informatif tanpa join
        GeneratedCoupon coupon = new GeneratedCoupon();
        coupon.setPartnerId(partnerId);
        coupon.setIdempotencyKey(req.idempotencyKey());
        coupon.setCouponId(req.couponId());
        coupon.setTransactionId(req.transactionId());
        coupon.setCouponCode(code);
        coupon.setSku(source.getSku());
        // Periode yang disimpan = periode yang baru saja divalidasi
        coupon.setCouponName(campaign.getName());
        coupon.setDiscPercentage(campaign.getDiscPercentage());
        coupon.setScanType(campaign.getTypeScan());
        coupon.setStartDate(campaign.getStartDate());
        coupon.setEndDate(campaign.getEndDate());
        coupon.setTag(tag);
        coupon.setState(text(line, "state"));
        coupon.setIsUsed(line.path("is_used").asBoolean(false));
        coupon.setTermsText(text(node, "terms_text"));
        coupon.setStatus(CouponStatus.ACTIVE);
        coupon.setCreatedAt(Instant.now());
        coupon.setRawJson(line.toString());
        return coupon;
    }

    /**
     * Gabungan data campaign: nilai dari template portal terbaru bila field-nya ada di respons,
     * selain itu nilai hasil sync lokal. Tanggal yang ada tetapi tidak bisa di-parse → ditolak,
     * agar periode tidak diam-diam jatuh ke data lama.
     */
    private Coupon currentCampaign(Coupon local, JsonNode node) {
        Coupon fresh = couponSyncService.toCoupon(node);
        requireParsed(node, "start_date", fresh.getStartDate(), local.getId());
        requireParsed(node, "end_date", fresh.getEndDate(), local.getId());

        Coupon c = new Coupon();
        c.setId(local.getId());
        c.setSku(local.getSku());
        c.setActive(has(node, "active") ? fresh.getActive() : local.getActive());
        c.setStartDate(has(node, "start_date") ? fresh.getStartDate() : local.getStartDate());
        c.setEndDate(has(node, "end_date") ? fresh.getEndDate() : local.getEndDate());
        c.setDays(has(node, "day_of_week_ids") ? fresh.getDays() : local.getDays());
        c.setName(fresh.getName() != null ? fresh.getName() : local.getName());
        c.setDiscPercentage(fresh.getDiscPercentage() != null ? fresh.getDiscPercentage() : local.getDiscPercentage());
        c.setTypeScan(fresh.getTypeScan() != null ? fresh.getTypeScan() : local.getTypeScan());
        c.setIsInjourney(fresh.getIsInjourney() != null ? fresh.getIsInjourney() : local.getIsInjourney());
        return c;
    }

    private static boolean has(JsonNode node, String field) {
        return !node.path(field).isMissingNode();
    }

    /** Field tanggal berisi teks tetapi gagal di-parse → respons portal tidak bisa dipercaya. */
    private static void requireParsed(JsonNode node, String field, Object parsed, Long couponId) {
        JsonNode v = node.path(field);
        if (v.isTextual() && !v.asText().isBlank() && parsed == null) {
            throw new PortalResponseException("Template coupon " + couponId + ": " + field
                    + " tidak valid '" + v.asText() + "'");
        }
    }

    /**
     * Template portal yang identitasnya cocok dengan campaign (id DAN sku), bukan sekadar elemen
     * pertama, dan skd_line-nya berbentuk array.
     */
    private JsonNode matchingTemplate(ArrayNode result, Coupon source) {
        for (JsonNode n : result) {
            boolean sameId = String.valueOf(source.getId()).equals(text(n, "id"));
            boolean sameSku = source.getSku() == null || source.getSku().equals(text(n, "sku"));
            if (sameId && sameSku) {
                if (!n.path("skd_line").isArray()) {
                    throw new PortalResponseException("Template coupon " + source.getId() + " dari portal tanpa skd_line");
                }
                return n;
            }
        }
        throw new PortalResponseException("Portal tidak mengembalikan template coupon " + source.getId()
                + " (sku " + source.getSku() + ")");
    }

    private void validate(GenerateCouponRequest req) {
        if (req == null) throw new InvalidRequestException("Body tidak boleh kosong");
        if (req.idempotencyKey() == null || req.idempotencyKey().isBlank())
            throw new InvalidRequestException("idempotencyKey wajib diisi");
        if (req.couponId() == null)
            throw new InvalidRequestException("couponId wajib diisi");
    }

    /**
     * Pilih skd_line pertama yang BELUM dipakai (is_used = false) dan kodenya
     * BELUM pernah kita tag di DB. Mengembalikan null bila semua sudah terpakai/ter-tag.
     */
    private JsonNode pickAvailableLine(JsonNode node) {
        for (JsonNode line : node.path("skd_line")) {
            if (line.path("is_used").asBoolean(false)) continue;     // sudah dipakai
            String skd = text(line, "skd");
            if (skd == null) continue;
            if (generatedRepo.existsByCouponCode(skd)) continue;     // sudah kita tag
            return line;
        }
        return null;
    }

    private String text(JsonNode n, String f) {
        JsonNode v = n.path(f);
        if (v.isMissingNode() || v.isNull()) return null;
        if (v.isBoolean() && !v.asBoolean()) return null;
        return v.asText();
    }
}
