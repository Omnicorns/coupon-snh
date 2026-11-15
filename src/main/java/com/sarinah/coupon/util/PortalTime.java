package com.sarinah.coupon.util;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Satu-satunya sumber zona waktu untuk tanggal dari portal (start_date, end_date, write_date).
 *
 * Portal mengirim tanggal tanpa zona; semua perbandingan "sekarang" dan semua format ISO di
 * response/webhook harus memakai zona yang sama, kalau tidak campaign bisa dianggap aktif /
 * kedaluwarsa beberapa jam terlalu cepat atau terlambat.
 */
@Component
public class PortalTime {

    private final ZoneId zone;
    private final Clock clock;

    public PortalTime(@Value("${sarinah-portal.timezone:Asia/Jakarta}") String zone) {
        this.zone = ZoneId.of(zone);
        this.clock = Clock.system(this.zone);
    }

    public ZoneId zone() {
        return zone;
    }

    /** Waktu sekarang dalam zona portal, sebanding dengan tanggal yang disimpan dari portal. */
    public LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    /** Tanggal portal → ISO-8601 dengan offset, mis. 2026-09-23T20:18:49+07:00. Null tetap null. */
    public String toIsoOffset(LocalDateTime t) {
        return t == null ? null : t.atZone(zone).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }
}
