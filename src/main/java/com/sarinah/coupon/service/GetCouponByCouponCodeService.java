package com.sarinah.coupon.service;

import com.sarinah.coupon.dto.GenerateCouponByCouponResponse;
import com.sarinah.coupon.exception.CouponNotFoundException;
import com.sarinah.coupon.exception.InvalidRequestException;
import com.sarinah.coupon.repository.GeneratedCouponRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class GetCouponByCouponCodeService {
    private final GeneratedCouponRepository generatedCouponRepository;
    private final GenerateCouponCodeMapper transformer;


    public GenerateCouponByCouponResponse getByCouponCode(String couponCode) {
        if (couponCode == null || couponCode.isBlank()) {
            throw new InvalidRequestException("Kode kupon wajib diisi");
        }
        // Kode kupon = skd (string, bisa berisi huruf) → jangan di-parse ke angka.
        return generatedCouponRepository.findByCouponCode(couponCode)
                .map(transformer::toResponse)
                .orElseThrow(() -> new CouponNotFoundException(couponCode));
    }



}
