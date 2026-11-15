package com.sarinah.coupon.service;

import com.sarinah.coupon.dto.GenerateCouponNewResponse;

import com.sarinah.coupon.exception.CouponNotFoundException;
import com.sarinah.coupon.repository.GeneratedCouponRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class GetCouponByIdService {
    private final GeneratedCouponRepository generatedCouponRepository;
    private final GeneratedCouponNewMapper transformer;


    /** Kupon terbit milik partner untuk campaign {@code id}; kupon partner lain tidak pernah ikut. */
    public List<GenerateCouponNewResponse> getCouponById(String partnerId, Long id) {
        var coupon = generatedCouponRepository.findByCouponIdAndPartnerId(id, partnerId)
                .stream().map(transformer::toResponse).toList();
        if (coupon.isEmpty()){
            throw  new CouponNotFoundException(id);
        }


        return coupon;
    }
}
