package com.sarinah.coupon.configuration;


import com.sarinah.coupon.webhook.PrimeWebhookProperties;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.io.SocketConfig;
import org.apache.hc.core5.util.Timeout;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class PrimeWebhookRestClientConfiguration {

    @Bean(name = "primeWebhookRestClient")
    public RestClient primeWebhookRestClient(RestClient.Builder builder, PrimeWebhookProperties props) {
        Timeout connect = Timeout.ofMilliseconds(props.getConnectTimeout().toMillis());
        Timeout read = Timeout.ofMilliseconds(props.getReadTimeout().toMillis());

        PoolingHttpClientConnectionManager cm = PoolingHttpClientConnectionManagerBuilder.create()
                .setMaxConnTotal(10)
                .setMaxConnPerRoute(10)
                .setDefaultSocketConfig(SocketConfig.custom().setSoTimeout(read).build())
                .build(); // SSL default: sertifikat & hostname diverifikasi

        RequestConfig rc = RequestConfig.custom()
                .setConnectTimeout(connect)
                .setResponseTimeout(read)
                .setConnectionRequestTimeout(Timeout.ofSeconds(5))
                .build();

        CloseableHttpClient client = HttpClients.custom()
                .setConnectionManager(cm)
                .setDefaultRequestConfig(rc)
                .disableAutomaticRetries()
                .build();

        return builder
                .requestFactory(new HttpComponentsClientHttpRequestFactory(client))
                .build();
    }
}
