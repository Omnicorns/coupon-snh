package com.sarinah.coupon.repository;

import com.sarinah.coupon.entity.CouponStatus;
import com.sarinah.coupon.entity.GeneratedCoupon;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface GeneratedCouponRepository extends JpaRepository<GeneratedCoupon, Long> {

    Optional<GeneratedCoupon> findByIdempotencyKey(String idempotencyKey);

    boolean existsByCouponCode(String couponCode);

    /** Untuk sync status internal (lintas partner). Jangan dipakai di endpoint publik. */
    List<GeneratedCoupon> findByIsUsedFalse();

    // ---------- Endpoint publik: SELALU dibatasi partner dari autentikasi ----------

    Optional<GeneratedCoupon> findByCouponCodeAndPartnerId(String couponCode, String partnerId);

    List<GeneratedCoupon> findByCouponIdAndPartnerId(Long couponId, String partnerId);

    /** Kupon milik partner yang masih bisa dipakai: ACTIVE, belum dipakai, dan dalam periode berlaku. */
    @Query("""
            select c from GeneratedCoupon c
             where c.partnerId = :partnerId
               and c.status = com.sarinah.coupon.entity.CouponStatus.ACTIVE
               and c.isUsed = false
               and (c.startDate is null or c.startDate <= :now)
               and (c.endDate is null or c.endDate > :now)
             order by c.createdAt
            """)
    List<GeneratedCoupon> findAvailable(@Param("partnerId") String partnerId,
                                        @Param("now") LocalDateTime now);

    @Query("""
            select c from GeneratedCoupon c
             where c.partnerId = :partnerId
               and c.tag = :tag
               and c.status = com.sarinah.coupon.entity.CouponStatus.ACTIVE
               and c.isUsed = false
               and (c.startDate is null or c.startDate <= :now)
               and (c.endDate is null or c.endDate > :now)
             order by c.createdAt
            """)
    List<GeneratedCoupon> findAvailableByTag(@Param("partnerId") String partnerId,
                                             @Param("tag") String tag,
                                             @Param("now") LocalDateTime now);

    // ---------- Transisi status bersyarat (aman untuk beberapa instance sync) ----------

    /**
     * Ubah status hanya bila status saat ini masih salah satu dari {@code from}.
     * 0 = instance lain sudah lebih dulu mengubahnya (mis. sudah REDEEMED) → jangan timpa.
     */
    @Modifying
    @Query("""
            update GeneratedCoupon c set c.status = :target
             where c.id = :id and c.status in :from and c.isUsed = false
            """)
    int transitionStatus(@Param("id") Long id,
                         @Param("target") CouponStatus target,
                         @Param("from") Collection<CouponStatus> from);

    /** Tandai REDEEMED (+ is_used, state, redeemDate) hanya bila belum REDEEMED. */
    @Modifying
    @Query("""
            update GeneratedCoupon c
               set c.status = com.sarinah.coupon.entity.CouponStatus.REDEEMED,
                   c.isUsed = true, c.state = :state, c.redeemDate = :redeemDate
             where c.id = :id and c.status in :from
            """)
    int markRedeemed(@Param("id") Long id,
                     @Param("state") String state,
                     @Param("redeemDate") LocalDateTime redeemDate,
                     @Param("from") Collection<CouponStatus> from);
}
