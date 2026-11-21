package com.libra.streaming.core.integration.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** The pinned public key Analytics signs its bootstrap reads with (RA02.4, core-internal-analytics.v1). */
@ConfigurationProperties("libra.services.analytics")
public record AnalyticsServiceProperties(boolean enabled, String publicKey, String keyId) {
    public AnalyticsServiceProperties {
        if (enabled && (publicKey == null || publicKey.isBlank() || keyId == null || !keyId.matches("[A-Za-z0-9_-]{1,64}"))) {
            throw new IllegalArgumentException("Enabled Analytics service authentication requires a pinned RSA public key and key ID");
        }
    }
    @Override public String toString() { return "AnalyticsServiceProperties[enabled=" + enabled + ", key redacted]"; }
}
