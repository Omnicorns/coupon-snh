package com.sarinah.coupon.scheduler;


import com.sarinah.coupon.service.GetCouponIsUsedService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class CouponIsUsedScheduler {
    private final GetCouponIsUsedService getCouponIsUsedService;

    @Scheduled(cron = "${coupon.get.cron}", zone = "Asia/Jakarta")
    public void run() {
        try {

            // Isi filter bila portal mewajibkannya, mis:
            // request.put("name", ""); request.put("sku", "");
            getCouponIsUsedService.syncIsUsed();
        } catch (Exception e) {
            log.error("Sync coupon gagal —error get", e);
        }


    }}
