package com.sarinah.coupon.scheduler;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sarinah.coupon.service.CouponSyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Memicu sync berkala. Dibungkus try-catch agar kegagalan portal
 * tidak mematikan aplikasi — API publik tetap melayani data terakhir di DB.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CouponSyncScheduler {

    private final CouponSyncService syncService;

    @Scheduled(cron = "${coupon.sync.cron}", zone = "Asia/Jakarta")
    public void run() {
        try {
            ObjectNode request = JsonNodeFactory.instance.objectNode();
            // Isi filter bila portal mewajibkannya, mis:
            // request.put("name", ""); request.put("sku", "");
            syncService.sync(request);
        } catch (Exception e) {
            log.error("Sync coupon gagal — API publik tetap jalan dengan data terakhir", e);
        }
    }
}
