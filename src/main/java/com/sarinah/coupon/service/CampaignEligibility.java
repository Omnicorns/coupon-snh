package com.sarinah.coupon.service;

import com.sarinah.coupon.entity.Coupon;
import com.sarinah.coupon.exception.CampaignNotEligibleException;
import com.sarinah.coupon.util.PortalTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Memastikan campaign boleh menerbitkan kupon SAAT INI, sebelum kode dipilih:
 *  - active = true
 *  - start_date <= sekarang < end_date (zona {@link PortalTime})
 *  - hari ini termasuk day_of_week_ids (kosong = semua hari)
 *
 * Nama hari yang tidak dikenali membuat campaign ditolak (fail-closed) agar kupon tidak
 * terbit di hari yang sebenarnya tidak berlaku.
 */
@Component
@RequiredArgsConstructor
public class CampaignEligibility {

    private static final Map<String, DayOfWeek> DAY_NAMES = Map.ofEntries(
            Map.entry("monday", DayOfWeek.MONDAY), Map.entry("mon", DayOfWeek.MONDAY), Map.entry("senin", DayOfWeek.MONDAY),
            Map.entry("tuesday", DayOfWeek.TUESDAY), Map.entry("tue", DayOfWeek.TUESDAY), Map.entry("selasa", DayOfWeek.TUESDAY),
            Map.entry("wednesday", DayOfWeek.WEDNESDAY), Map.entry("wed", DayOfWeek.WEDNESDAY), Map.entry("rabu", DayOfWeek.WEDNESDAY),
            Map.entry("thursday", DayOfWeek.THURSDAY), Map.entry("thu", DayOfWeek.THURSDAY), Map.entry("kamis", DayOfWeek.THURSDAY),
            Map.entry("friday", DayOfWeek.FRIDAY), Map.entry("fri", DayOfWeek.FRIDAY), Map.entry("jumat", DayOfWeek.FRIDAY),
            Map.entry("saturday", DayOfWeek.SATURDAY), Map.entry("sat", DayOfWeek.SATURDAY), Map.entry("sabtu", DayOfWeek.SATURDAY),
            Map.entry("sunday", DayOfWeek.SUNDAY), Map.entry("sun", DayOfWeek.SUNDAY), Map.entry("minggu", DayOfWeek.SUNDAY),
            Map.entry("ahad", DayOfWeek.SUNDAY));

    private final PortalTime time;

    /** @throws CampaignNotEligibleException bila campaign tidak boleh menerbitkan kupon sekarang */
    public void check(Coupon c) {
        check(c, time.now());
    }

    void check(Coupon c, LocalDateTime now) {
        Long id = c.getId();
        if (!Boolean.TRUE.equals(c.getActive())) {
            throw new CampaignNotEligibleException(id, "campaign tidak aktif");
        }
        if (c.getStartDate() != null && now.isBefore(c.getStartDate())) {
            throw new CampaignNotEligibleException(id, "campaign belum dimulai (mulai " + time.toIsoOffset(c.getStartDate()) + ")");
        }
        if (c.getEndDate() != null && !now.isBefore(c.getEndDate())) {
            throw new CampaignNotEligibleException(id, "campaign sudah berakhir (" + time.toIsoOffset(c.getEndDate()) + ")");
        }
        Set<DayOfWeek> allowed = parseDays(id, c.getDays());
        if (!allowed.isEmpty() && !allowed.contains(now.getDayOfWeek())) {
            throw new CampaignNotEligibleException(id, "campaign tidak berlaku pada hari "
                    + now.getDayOfWeek().name().toLowerCase(Locale.ROOT));
        }
    }

    /** Daftar kosong = berlaku setiap hari; elemen kosong/tidak dikenali = ditolak. */
    static Set<DayOfWeek> parseDays(Long couponId, List<String> names) {
        Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
        if (names == null) return days;
        for (String raw : names) {
            // Elemen tanpa nama (mis. day_of_week_ids: [{}]) adalah data cacat, BUKAN "tanpa batasan
            // hari" → tolak. Hanya daftar yang benar-benar kosong yang berarti berlaku setiap hari.
            if (raw == null || raw.isBlank()) {
                throw new CampaignNotEligibleException(couponId, "aturan hari campaign tidak valid (nama hari kosong)");
            }
            String key = raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
            DayOfWeek d = DAY_NAMES.get(key);
            if (d == null) {
                throw new CampaignNotEligibleException(couponId, "aturan hari tidak dikenali: '" + raw + "'");
            }
            days.add(d);
        }
        return days;
    }
}
