package com.libra.streaming.core.integration.analytics;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("libra.analytics")
public record AnalyticsProperties(boolean enabled, URI baseUrl, String privateKey, String publicKey, String keyId) {
    public AnalyticsProperties {
        if (enabled && (baseUrl == null || baseUrl.getHost() == null || baseUrl.getUserInfo() != null
                || baseUrl.getQuery() != null || baseUrl.getFragment() != null
                || !(baseUrl.getPath().isEmpty() || baseUrl.getPath().equals("/"))
                || !("https".equals(baseUrl.getScheme()) || "http".equals(baseUrl.getScheme()))
                || privateKey == null || privateKey.isBlank() || publicKey == null || publicKey.isBlank()
                || keyId == null || !keyId.matches("[A-Za-z0-9_-]{1,64}"))) {
            throw new IllegalArgumentException("Enabled Analytics requires an HTTP(S) service origin and dedicated RSA keys/key ID");
        }
    }
    @Override public String toString() { return "AnalyticsProperties[enabled=" + enabled + ", secrets redacted]"; }
}
