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

        String code = text(node, "skd_code");
        if (code == null) throw new InvalidRequestException("Portal tidak mengembalikan skd_code");

        // Kode yang sudah pernah di-tag TIDAK boleh diterbitkan ulang.
        // Kalau portal masih mengembalikan kode lama, berarti belum ada kode baru
        // (mis. kuota campaign habis) → tolak, jangan bagikan ulang.
        if (generatedRepo.existsByCouponCode(code)) {
            log.info("Kode {} sudah pernah di-tag → tolak (belum ada kode baru dari portal)", code);
            throw new CouponExhaustedException(req.couponId(), code);
        }

        String termsText = text(node, "terms_text");
        JsonNode line = findSkdLine(node, code);
        boolean isUsed = line != null && line.path("is_used").asBoolean(false);
        String state = line != null ? text(line, "state") : null;

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

    private JsonNode findSkdLine(JsonNode node, String code) {
        for (JsonNode line : node.path("skd_line")) {
            if (code.equals(text(line, "skd"))) return line;
        }
        JsonNode lines = node.path("skd_line");
        return lines.isArray() && !lines.isEmpty() ? lines.get(0) : null;
    }

    private String text(JsonNode n, String f) {
        JsonNode v = n.path(f);
        if (v.isMissingNode() || v.isNull()) return null;
        if (v.isBoolean() && !v.asBoolean()) return null;
        return v.asText();
    }
}
