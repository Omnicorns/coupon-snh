package com.sarinah.coupon.service;

import com.sarinah.coupon.dto.GenerateCouponNewResponse;
import com.sarinah.coupon.dto.GenerateCouponResponse;

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


    public List<GenerateCouponNewResponse> getCouponById(Long id) {
        var coupon = generatedCouponRepository.findByCouponId(id)
                .stream().map(transformer::toResponse).toList();
        if (coupon.isEmpty()){
            throw  new CouponNotFoundException(id);
        }


        return coupon;
    }
}
