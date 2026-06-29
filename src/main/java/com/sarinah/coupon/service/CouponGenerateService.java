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
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Menerbitkan kode kupon (= skd dari portal) untuk sebuah coupon sumber.
 * idempotencyKey memastikan retry tidak mencetak kode ganda.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CouponGenerateService {

    private final GeneratedCouponRepository generatedRepo;
    private final CouponRepository couponRepo;
    private final PostCouponListService postCouponListService;
    private final GeneratedCouponMapper mapper;

    @Transactional
    public GenerateCouponResponse generate(GenerateCouponRequest req) {
        validate(req);

        // 1) Idempotensi — retry tidak memanggil portal lagi
        var existing = generatedRepo.findByIdempotencyKey(req.idempotencyKey());
        if (existing.isPresent()) {
            log.info("Idempotent hit key={}", req.idempotencyKey());
            return mapper.toResponse(existing.get());
        }

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

        String termsText = text(node, "terms_text");
        boolean isUsed = line.path("is_used").asBoolean(false);
        String state = text(line, "state");

        String tag = (req.tag() != null && !req.tag().isBlank())
                ? req.tag()
                : (Boolean.TRUE.equals(source.getIsInjourney()) ? "sarinah-injourney" : "sarinah");

        // 4) Simpan (ringkasan campaign ikut, supaya response informatif tanpa join)
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
        coupon.setState(state);
        coupon.setIsUsed(isUsed);
        coupon.setTermsText(termsText);
        coupon.setStatus(CouponStatus.ACTIVE);
        coupon.setCreatedAt(Instant.now());
        coupon.setRawJson(line != null ? line.toString() : node.toString());

        try {
            generatedRepo.saveAndFlush(coupon);   // flush sekarang agar error muncul di sini
        } catch (DataIntegrityViolationException e) {
            // Race: request lain menyisipkan kode/key yang sama bersamaan.
            // JANGAN query lagi di transaksi ini (sesi sudah rusak) → minta client retry.
            log.warn("Konflik penyimpanan code={} key={}", code, req.idempotencyKey());
            throw new ConflictException("Kupon sedang diterbitkan request lain, silakan ulangi");
        }

        log.info("Kupon terbit: id={}, code={}, dari couponId={}, tag={}",
                coupon.getId(), code, req.couponId(), tag);
        return mapper.toResponse(coupon);
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
