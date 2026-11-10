package com.sarinah.coupon.service;

import com.sarinah.coupon.dto.GenerateCouponRequest;
import com.sarinah.coupon.entity.CouponGenerateRequest;
import com.sarinah.coupon.entity.GenerateRequestStatus;
import com.sarinah.coupon.exception.ConflictException;
import com.sarinah.coupon.exception.IdempotencyKeyReusedException;
import com.sarinah.coupon.repository.CouponGenerateRequestRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/**
 * Klaim idempotency atomik untuk generate kupon.
 *
 * Setiap method berjalan di transaksi pendek sendiri (REQUIRES_NEW) supaya klaim langsung
 * ter-commit dan terlihat oleh request lain sebelum portal dipanggil.
 */
@Slf4j
@Service
public class GenerateIdempotencyService {

    private final CouponGenerateRequestRepository repo;
    private final Duration lease;

    public GenerateIdempotencyService(
            CouponGenerateRequestRepository repo,
            @Value("${coupon.generate.processing-lease:PT2M}") Duration lease) {
        this.repo = repo;
        this.lease = lease;
    }

    /** Hasil klaim: kita pemiliknya (token), atau request ini sudah selesai sebelumnya (couponId). */
    public record Claim(String token, Long completedCouponId) {
        public boolean owned() { return token != null; }
    }

    /**
     * Klaim key secara atomik.
     *
     * @return Claim milik kita, atau Claim berisi id kupon bila sudah COMPLETED
     * @throws ConflictException             bila request lain sedang memproses key ini
     * @throws IdempotencyKeyReusedException bila key dipakai dengan isi request berbeda
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Claim acquire(GenerateCouponRequest req) {
        String key = req.idempotencyKey();
        String hash = fingerprint(req);
        Instant now = Instant.now();
        String token = UUID.randomUUID().toString();

        if (repo.insertClaim(key, hash, token, now.plus(lease), now) == 1) {
            return new Claim(token, null);
        }

        CouponGenerateRequest row = repo.findById(key)
                .orElseThrow(() -> new ConflictException("Kupon sedang diterbitkan request lain, silakan ulangi"));

        if (!row.getRequestHash().equals(hash)) {
            throw new IdempotencyKeyReusedException(key);
        }
        if (row.getStatus() == GenerateRequestStatus.COMPLETED) {
            log.info("Idempotent hit key={}", key);
            return new Claim(null, row.getGeneratedCouponId());
        }
        if (repo.takeOver(key, hash, token, now.plus(lease), now) == 1) {
            log.info("Ambil alih klaim key={} (status sebelumnya={})", key, row.getStatus());
            return new Claim(token, null);
        }
        log.info("Key {} sedang diproses request lain", key);
        throw new ConflictException("Kupon dengan idempotencyKey ini sedang diproses, silakan ulangi beberapa saat lagi");
    }

    /** Lepas klaim sebagai FAILED (hanya bila kita masih pemiliknya) agar retry bisa mencoba lagi. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(String key, String token, Throwable cause) {
        String msg = cause.getClass().getSimpleName() + ": " + Objects.toString(cause.getMessage(), "");
        if (msg.length() > 500) msg = msg.substring(0, 500);
        repo.release(key, token, msg, Instant.now());
    }

    /** Dipanggil di DALAM transaksi penyimpanan kupon; 0 berarti klaim sudah hilang (lease habis & diambil alih). */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean complete(String key, String token, Long couponId) {
        return repo.complete(key, token, couponId, Instant.now()) == 1;
    }

    static String fingerprint(GenerateCouponRequest req) {
        String tag = (req.tag() == null || req.tag().isBlank()) ? "" : req.tag();
        String raw = req.couponId() + "\u001F" + Objects.toString(req.transactionId(), "") + "\u001F" + tag;
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
