package com.sarinah.coupon.repository;

import com.sarinah.coupon.entity.GeneratedCoupon;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface GeneratedCouponRepository extends JpaRepository<GeneratedCoupon, Long> {

    Optional<GeneratedCoupon> findByIdempotencyKey(String idempotencyKey);

    Optional<GeneratedCoupon> findByCouponCode(String couponCode);

    List<GeneratedCoupon> findByIsUsedFalse();
    List<GeneratedCoupon> findByCouponId(Long couponId);

    List<GeneratedCoupon> findByTagAndIsUsedFalse(String tag);
    boolean existsByCouponCode(String couponCode);
}
