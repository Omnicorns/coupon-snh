package com.sarinah.coupon.configuration;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Autentikasi pihak ketiga via header X-API-Key.
 * Key dikonfigurasi di application.properties sebagai "key:partner" dipisah koma.
 * Hanya endpoint publik (/api/v1/**) yang diproteksi.
 */
@Slf4j
@Configuration
public class ApiKeyConfig {

    public static final String HEADER = "X-API-Key";

    /** Parse "keyAAA:partner-a,keyBBB:partner-b" → map key→partner. */
    @Bean
    public Map<String, String> apiKeyMap(@Value("${coupon.security.api-keys:}") String raw) {
        Map<String, String> map = new HashMap<>();
        if (raw != null && !raw.isBlank()) {
            for (String pair : raw.split(",")) {
                String[] kv = pair.split(":", 2);
                if (kv.length == 2) {
                    map.put(kv[0].trim(), kv[1].trim());
                }
            }
        }
        log.info("API key dimuat untuk {} partner", map.size());
        return map;
    }

    @Bean
    public FilterRegistrationBean<OncePerRequestFilter> apiKeyFilter(Map<String, String> apiKeyMap) {
        OncePerRequestFilter filter = new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res,
                                            FilterChain chain) throws ServletException, IOException {
                String key = req.getHeader(HEADER);
                if (key == null || !apiKeyMap.containsKey(key)) {
                    res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    res.setContentType("application/json");
                    res.getWriter().write("{\"status\":401,\"error\":\"invalid or missing " + HEADER + "\"}");
                    return;
                }
                String partner = apiKeyMap.get(key);
                long start = System.currentTimeMillis();
                try {
                    chain.doFilter(req, res);
                } finally {
                    log.info("partner={} {} {} -> {} ({}ms)", partner, req.getMethod(),
                            req.getRequestURI(), res.getStatus(), System.currentTimeMillis() - start);
                }
            }
        };

        FilterRegistrationBean<OncePerRequestFilter> reg = new FilterRegistrationBean<>(filter);
        reg.addUrlPatterns("/api/v1/*");   // hanya endpoint publik
        reg.setOrder(1);
        return reg;
    }
}
