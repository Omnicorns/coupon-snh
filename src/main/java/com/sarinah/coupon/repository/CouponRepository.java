package com.sarinah.coupon.repository;

import com.sarinah.coupon.entity.Coupon;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface CouponRepository extends JpaRepository<Coupon, Long> {

    List<Coupon> findByActiveTrue();

    /** Kupon yang aktif & berada dalam rentang waktu tertentu (mis. berlaku "sekarang"). */
    List<Coupon> findByActiveTrueAndStartDateBeforeAndEndDateAfter(
            LocalDateTime upperStart, LocalDateTime lowerEnd);
}
