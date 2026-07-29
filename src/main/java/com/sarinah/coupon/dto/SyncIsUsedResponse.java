package com.sarinah.coupon.dto;

import java.util.List;

public record SyncIsUsedResponse(
        int checked,          // jumlah kupon pending yang diperiksa
        int updated,          // jumlah yang berubah jadi USED
        List<String> failedSkus // SKU yang gagal dipanggil/diproses


) {
   // SKU yang gagal dipanggil/diproses

}
