package com.sarinah.coupon.service;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sarinah.coupon.adaptor.SarinahGetModulAdaptor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Titik masuk pengambilan data coupon dari portal.
 * Tipis: mendelegasikan ke adaptor. Dipisah agar mudah diganti/di-mock.
 */
@Service
@RequiredArgsConstructor
public class PostCouponListService {

    private final SarinahGetModulAdaptor sarinahGetModulAdaptor;

    public ArrayNode execute(ObjectNode request) {
        return sarinahGetModulAdaptor.getCoupon(request);
    }
}
