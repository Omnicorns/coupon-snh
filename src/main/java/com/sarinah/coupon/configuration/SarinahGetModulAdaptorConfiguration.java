package com.sarinah.coupon.configuration;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.httpcomponents.hc5.PoolingHttpClientConnectionManagerMetricsBinder;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.socket.ConnectionSocketFactory;
import org.apache.hc.client5.http.socket.PlainConnectionSocketFactory;
import org.apache.hc.client5.http.ssl.NoopHostnameVerifier;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactory;
import org.apache.hc.core5.http.config.RegistryBuilder;
import org.apache.hc.core5.http.io.SocketConfig;
import org.apache.hc.core5.ssl.SSLContextBuilder;
import org.apache.hc.core5.ssl.TrustStrategy;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import javax.net.ssl.SSLContext;
import java.io.IOException;

/**
 * Konfigurasi RestClient untuk memanggil portal Sarinah.
 *
 * Portal berada di IP internal dengan sertifikat self-signed, sehingga verifikasi
 * TLS di-bypass (trust-all). Ini AMAN HANYA untuk endpoint internal tepercaya —
 * jangan dipakai untuk memanggil host publik di internet.
 */
@Configuration
public class SarinahGetModulAdaptorConfiguration {

    @Value("${http.client.max-connections}")
    private int maxTotalConnections;
    @Value("${http.client.max-per-route}")
    private int maxPerRoute;
    @Value("${http.client.connection-request-timeout}")
    private int connectionRequestTimeout;
    @Value("${http.client.connect-timeout}")
    private int connectTimeout;
    @Value("${http.client.read-timeout}")
    private int readTimeout;

    @Bean(name = "defaultPointRestClient")
    public RestClient defaultPointRestClient(RestClient.Builder builder,
                                             MeterRegistry meterRegistry) throws Exception {
        return builder
                .requestFactory(requestFactory(meterRegistry))
                // portal sering mengembalikan status error untuk kasus normal → tangani manual di adaptor
                .defaultStatusHandler(status -> status.isError(), (req, res) -> { /* no-op */ })
                .requestInterceptor(new RetryOnServerErrorInterceptor(1, 300))
                .build();
    }

    private HttpComponentsClientHttpRequestFactory requestFactory(MeterRegistry meterRegistry)
            throws Exception {

        SSLContext sslContext = SSLContextBuilder.create()
                .loadTrustMaterial(null, (TrustStrategy) (chain, authType) -> true)
                .build();

        var socketRegistry = RegistryBuilder.<ConnectionSocketFactory>create()
                .register("https", new SSLConnectionSocketFactory(sslContext, NoopHostnameVerifier.INSTANCE))
                .register("http", PlainConnectionSocketFactory.getSocketFactory())
                .build();

        PoolingHttpClientConnectionManager cm = new PoolingHttpClientConnectionManager(socketRegistry);
        cm.setMaxTotal(maxTotalConnections);
        cm.setDefaultMaxPerRoute(maxPerRoute);
        cm.setDefaultSocketConfig(SocketConfig.custom()
                .setSoTimeout(Timeout.ofMilliseconds(readTimeout)).build());

        new PoolingHttpClientConnectionManagerMetricsBinder(cm, "httpClientPool").bindTo(meterRegistry);

        RequestConfig rc = RequestConfig.custom()
                .setConnectTimeout(Timeout.ofMilliseconds(connectTimeout))
                .setResponseTimeout(Timeout.ofMilliseconds(readTimeout))
                .setConnectionRequestTimeout(Timeout.ofMilliseconds(connectionRequestTimeout))
                .build();

        CloseableHttpClient client = HttpClients.custom()
                .setConnectionManager(cm)
                .setDefaultRequestConfig(rc)
                .build();

        return new HttpComponentsClientHttpRequestFactory(client);
    }

    /**
     * Mencoba ulang saat portal membalas 5xx (mis. halaman blokir WAF sementara).
     * Percobaan terakhir dikembalikan apa adanya tanpa melempar exception.
     */
    static class RetryOnServerErrorInterceptor implements ClientHttpRequestInterceptor {
        private final int maxExtraAttempts;
        private final long backoffMillis;

        RetryOnServerErrorInterceptor(int maxExtraAttempts, long backoffMillis) {
            this.maxExtraAttempts = maxExtraAttempts;
            this.backoffMillis = backoffMillis;
        }

        @Override
        public ClientHttpResponse intercept(HttpRequest request, byte[] body,
                                            ClientHttpRequestExecution execution) throws IOException {
            int total = 1 + maxExtraAttempts;
            for (int attempt = 1; attempt <= total; attempt++) {
                ClientHttpResponse resp = execution.execute(request, body);
                if (resp.getStatusCode().value() != 500 || attempt == total) {
                    return resp;
                }
                try {
                    resp.close();
                    Thread.sleep(backoffMillis * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
            return execution.execute(request, body); // unreachable
        }
    }
}
