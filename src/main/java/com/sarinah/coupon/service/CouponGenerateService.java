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
    public GenerateCouponResponse generate(GenerateCouponRequest req) {
        validate(req);
        String key = req.idempotencyKey();

        // 1) Klaim atomik
        GenerateIdempotencyService.Claim claim = idempotency.acquire(req);
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
                tx.executeWithoutResult(s -> idempotency.complete(key, token, legacy.get().getId()));
                log.info("Idempotent hit (data lama) key={}", key);
                return mapper.toResponse(legacy.get());
            }

            GeneratedCoupon coupon = issue(req);

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
    private GeneratedCoupon issue(GenerateCouponRequest req) {
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
        JsonNode node = result.get(0);

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
                : (Boolean.TRUE.equals(source.getIsInjourney()) ? "sarinah-injourney" : "sarinah");

        // Ringkasan campaign ikut, supaya response informatif tanpa join
        GeneratedCoupon coupon = new GeneratedCoupon();
        coupon.setIdempotencyKey(req.idempotencyKey());
        coupon.setCouponId(req.couponId());
        coupon.setTransactionId(req.transactionId());
        coupon.setCouponCode(code);
        coupon.setSku(source.getSku());
        coupon.setCouponName(source.getName());
        coupon.setDiscPercentage(source.getDiscPercentage());
        coupon.setScanType(source.getTypeScan());
        coupon.setStartDate(source.getStartDate());
        coupon.setEndDate(source.getEndDate());
        coupon.setTag(tag);
        coupon.setState(text(line, "state"));
        coupon.setIsUsed(line.path("is_used").asBoolean(false));
        coupon.setTermsText(text(node, "terms_text"));
        coupon.setStatus(CouponStatus.ACTIVE);
        coupon.setCreatedAt(Instant.now());
        coupon.setRawJson(line.toString());
        return coupon;
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
