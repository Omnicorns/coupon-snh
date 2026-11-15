package com.sarinah.coupon.exception;

/** Campaign ada, tetapi saat ini tidak boleh menerbitkan kupon (nonaktif, di luar periode/hari). → 422 */
public class CampaignNotEligibleException extends RuntimeException {
    public CampaignNotEligibleException(Long couponId, String reason) {
        super("Coupon " + couponId + " tidak dapat diterbitkan: " + reason);
    }
}
