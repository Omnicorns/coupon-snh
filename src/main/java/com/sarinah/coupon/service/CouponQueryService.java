package com.sarinah.coupon.service;

import com.sarinah.coupon.dto.CouponResponse;
import com.sarinah.coupon.exception.CouponNotFoundException;
import com.sarinah.coupon.repository.CouponRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Jalur BACA: dipakai pihak ketiga. Membaca dari DB kita (bukan dari portal),
 * lalu mengubah ke kontrak publik via transformer.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CouponQueryService {

    private final CouponRepository repo;
    private final CouponTransformer transformer;

    public List<CouponResponse> getAll() {
        return repo.findAll().stream().map(transformer::toResponse).toList();
    }

    public List<CouponResponse> getActive() {
        return repo.findByActiveTrue().stream().map(transformer::toResponse).toList();
    }

    /** Kupon yang berlaku saat ini (aktif & dalam rentang tanggal). */
    public List<CouponResponse> getValidNow() {
        LocalDateTime now = LocalDateTime.now();
        return repo.findByActiveTrueAndStartDateBeforeAndEndDateAfter(now, now)
                .stream().map(transformer::toResponse).toList();
    }

    public CouponResponse getById(Long id) {
        return repo.findById(id)
                .map(transformer::toResponse)
                .orElseThrow(() -> new CouponNotFoundException(id));
    }
}
