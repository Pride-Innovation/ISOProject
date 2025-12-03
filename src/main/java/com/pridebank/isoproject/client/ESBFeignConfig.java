package com.pridebank.isoproject.client;

import feign.Logger;
import feign.RequestInterceptor;
import feign.codec.ErrorDecoder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.net.ssl.*;
import java.security.cert.X509Certificate;

@Slf4j
@Configuration
public class ESBFeignConfig {

    @Value("${esb.connection-timeout:60000}")
    private int connectTimeout;

    @Value("${esb.read-timeout:60000}")
    private int readTimeout;

    /**
     * Configure Feign logging level
     */
    @Bean
    Logger.Level feignLoggerLevel() {
        return Logger.Level.FULL;
    }

    /**
     * Custom error decoder for ESB responses
     */
    @Bean
    public ErrorDecoder errorDecoder() {
        return new ESBErrorDecoder();
    }

    /**
     * Request options for timeouts
     */
    @Bean
    public feign.Request.Options requestOptions() {
        return new feign.Request.Options(
                connectTimeout,
                java.util.concurrent.TimeUnit.MILLISECONDS,
                readTimeout,
                java.util.concurrent.TimeUnit.MILLISECONDS,
                true
        );
    }

    /**
     * Request interceptor to log all requests
     */
    @Bean
    public RequestInterceptor requestInterceptor() {
        return requestTemplate -> {
            log.debug("Feign Request: {} {}",
                    requestTemplate.method(),
                    requestTemplate.url()
            );
            log.debug("Headers: {}", requestTemplate.headers());
        };
    }

    /**
     * Disable SSL verification (FOR TESTING ONLY)
     * WARNING: DO NOT USE IN PRODUCTION
     */
    @Bean
    public feign.Client feignClient() {
        return new feign.Client.Default(
                getSSLSocketFactory(),
                (hostname, session) -> true // NoopHostnameVerifier
        );
    }

    /**
     * Create SSL Socket Factory that trusts all certificates
     * WARNING: Use only for testing/development
     */
    private SSLSocketFactory getSSLSocketFactory() {
        try {
            TrustManager[] trustAllCerts = new TrustManager[]{
                    new X509TrustManager() {
                        public X509Certificate[] getAcceptedIssuers() {
                            return new X509Certificate[0];
                        }

                        public void checkClientTrusted(X509Certificate[] certs, String authType) {
                        }

                        public void checkServerTrusted(X509Certificate[] certs, String authType) {
                        }
                    }
            };

            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustAllCerts, new java.security.SecureRandom());

            log.warn("⚠️  SSL verification disabled - DO NOT USE IN PRODUCTION");

            return sslContext.getSocketFactory();
        } catch (Exception e) {
            throw new RuntimeException("Failed to create SSL context", e);
        }
    }
}
