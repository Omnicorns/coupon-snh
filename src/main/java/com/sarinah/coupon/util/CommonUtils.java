package com.sarinah.coupon.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.Iterator;
import java.util.Map;

@Component
public class CommonUtils {

    /** Membangun URI dengan query param dari setiap field pada request JSON. */
    public URI dynamicParamBuilder(ObjectNode request, String url) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(url);
        Iterator<Map.Entry<String, JsonNode>> it = request.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> entry = it.next();
            builder.queryParam(entry.getKey(), entry.getValue().asText());
        }
        return builder.build().encode().toUri();
    }
}
