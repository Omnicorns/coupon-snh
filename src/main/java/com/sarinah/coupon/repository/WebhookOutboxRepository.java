package com.sarinah.coupon.repository;

import com.sarinah.coupon.entity.WebhookOutbox;
import com.sarinah.coupon.entity.WebhookOutboxStatus;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface WebhookOutboxRepository extends JpaRepository<WebhookOutbox, Long> {

    boolean existsByCouponCodeAndEvent(String couponCode, String event);

    List<WebhookOutbox> findByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(
            WebhookOutboxStatus status, Instant now, Pageable pageable);

    List<WebhookOutbox> findByStatusOrderByCreatedAtDesc(WebhookOutboxStatus status, Pageable pageable);
}
