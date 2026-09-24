package com.sarinah.coupon.adaptor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sarinah.coupon.util.CommonUtils;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Outbound adapter ke portal Sarinah.
 *
 * Catatan penting: portal mengembalikan JSON tetapi melabelinya `text/html`,
 * sehingga kita TIDAK boleh meminta .body(JsonNode.class). Kita baca sebagai
 * String mentah lalu parse manual dengan ObjectMapper (tahan terhadap label
 * Content-Type yang salah).
 */
@RequiredArgsConstructor
@Component
@Slf4j
public class SarinahGetModulAdaptor {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Value("${sarinah-portal.coupon.url}")
    private String couponUrl;

    @Value("${sarinah-portal.coupon.order.url}")
    private String posOrderUrl;

    private final CommonUtils commonUtils;
    private final RestClient defaultPointRestClient;

    @SneakyThrows
    public ArrayNode getCoupon(ObjectNode request) {
        String raw = defaultPointRestClient
                .post()
                .uri(commonUtils.dynamicParamBuilder(request, couponUrl))
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_FORM_URLENCODED_VALUE)
                .retrieve()
                .body(String.class);                 // baca mentah, abaikan Content-Type

        if (raw == null || raw.isBlank()) {
            log.warn("Respons portal kosong");
            return JsonNodeFactory.instance.arrayNode();
        }

        JsonNode root;
        try {
            root = OBJECT_MAPPER.readTree(raw);      // tahan text/html berisi JSON
        } catch (Exception e) {
            // Bukan JSON (mis. halaman blokir WAF / login) → jangan matikan sync
            log.error("Respons portal bukan JSON valid. Cuplikan: {}",
                    raw.substring(0, Math.min(raw.length(), 300)));
            return JsonNodeFactory.instance.arrayNode();
        }

        if (root.isArray()) {
            return (ArrayNode) root;
        }
        ArrayNode arr = JsonNodeFactory.instance.arrayNode();
        arr.add(root);
        return arr;
    }

    public ArrayNode getPosHistory(ObjectNode request) {
        JsonNode root = defaultPointRestClient
                .post()
                .uri(commonUtils.dynamicParamBuilder(request,posOrderUrl))
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_FORM_URLENCODED_VALUE)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(JsonNode.class);;                 // baca sebagai JsonNode


        if (root == null) {
            return JsonNodeFactory.instance.arrayNode();
        }


        if (root.isArray()) {
            return (ArrayNode) root;
        }


        ArrayNode arr = JsonNodeFactory.instance.arrayNode();
        arr.add(root);
        return arr;

    }
}
