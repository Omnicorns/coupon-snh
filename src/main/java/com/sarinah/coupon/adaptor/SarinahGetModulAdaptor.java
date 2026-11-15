package com.sarinah.coupon.adaptor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sarinah.coupon.exception.PortalResponseException;
import com.sarinah.coupon.util.CommonUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Outbound adapter ke portal Sarinah.
 *
 * Catatan penting: portal mengembalikan JSON tetapi melabelinya `text/html`,
 * sehingga kita TIDAK boleh meminta .body(JsonNode.class). Kita baca sebagai
 * String mentah lalu parse manual dengan ObjectMapper (tahan terhadap label
 * Content-Type yang salah).
 *
 * Respons yang tidak bisa dipercaya (HTTP non-2xx, bukan JSON, object error) dilempar sebagai
 * {@link PortalResponseException}, BUKAN diubah menjadi data kosong/palsu: pemanggil (sync is_used)
 * memakai isi respons untuk memutuskan pencabutan kupon, jadi respons error tidak boleh terlihat
 * seperti "data tanpa skd_line".
 */
@RequiredArgsConstructor
@Component
@Slf4j
public class SarinahGetModulAdaptor {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Value("${sarinah-portal.coupon.url}")
    private String couponUrl;

    @Value("${sarinah-portal.coupon.order.url}")
    private String posOrderUrl;

    private final CommonUtils commonUtils;
    private final RestClient defaultPointRestClient;

    /**
     * Daftar template kupon. Setiap elemen dijamin object yang punya field "id".
     * Array kosong = portal benar-benar tidak punya data (respons 2xx berisi []).
     */
    public ArrayNode getCoupon(ObjectNode request) {
        JsonNode root = call(couponUrl, request, "coupon");
        ArrayNode arr = asArray(root, "coupon");
        for (JsonNode n : arr) {
            if (!n.isObject() || !hasValue(n, "id")) {
                throw new PortalResponseException("Respons coupon portal tidak berbentuk template (tanpa id): "
                        + snippet(n.toString()));
            }
        }
        return arr;
    }

    public ArrayNode getPosHistory(ObjectNode request) {
        return asArray(call(posOrderUrl, request, "order history"), "order history");
    }

    private JsonNode call(String url, ObjectNode request, String what) {
        ResponseEntity<String> resp = defaultPointRestClient
                .post()
                .uri(commonUtils.dynamicParamBuilder(request, url))
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_FORM_URLENCODED_VALUE)
                .retrieve()
                .toEntity(String.class);                 // baca mentah, abaikan Content-Type

        String raw = resp.getBody();
        if (!resp.getStatusCode().is2xxSuccessful()) {
            throw new PortalResponseException("Portal " + what + " membalas HTTP "
                    + resp.getStatusCode().value() + ": " + snippet(raw));
        }
        if (raw == null || raw.isBlank()) {
            throw new PortalResponseException("Respons portal " + what + " kosong");
        }
        try {
            JsonNode root = MAPPER.readTree(raw);        // tahan text/html berisi JSON
            if (root == null || root.isMissingNode() || root.isNull()) {
                throw new PortalResponseException("Respons portal " + what + " null");
            }
            return root;
        } catch (PortalResponseException e) {
            throw e;
        } catch (Exception e) {
            // Bukan JSON (mis. halaman blokir WAF / login)
            throw new PortalResponseException("Respons portal " + what + " bukan JSON valid: " + snippet(raw));
        }
    }

    /**
     * Array apa adanya. Object tunggal hanya diterima bila berbentuk data (punya "id")
     * dan bukan object error ({"error": ...} / {"status": "error"} dsb.).
     */
    private ArrayNode asArray(JsonNode root, String what) {
        if (root.isArray()) {
            return (ArrayNode) root;
        }
        if (root.isObject() && !looksLikeError(root) && hasValue(root, "id")) {
            ArrayNode arr = JsonNodeFactory.instance.arrayNode();
            arr.add(root);
            return arr;
        }
        throw new PortalResponseException("Respons portal " + what + " bukan data yang dikenali: "
                + snippet(root.toString()));
    }

    private static boolean looksLikeError(JsonNode n) {
        if (hasValue(n, "error") || hasValue(n, "errors")) return true;
        String status = n.path("status").asText("");
        return status.equalsIgnoreCase("error") || status.equalsIgnoreCase("failed")
                || n.path("success").isBoolean() && !n.path("success").asBoolean();
    }

    private static boolean hasValue(JsonNode n, String f) {
        JsonNode v = n.path(f);
        return !(v.isMissingNode() || v.isNull() || (v.isBoolean() && !v.asBoolean()));
    }

    private static String snippet(String raw) {
        if (raw == null) return "null";
        return raw.substring(0, Math.min(raw.length(), 300));
    }
}
