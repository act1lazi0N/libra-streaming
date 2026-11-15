package com.libra.streaming.recommendation.internalapi.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** The pinned public half of Core's dedicated Analytics service key. Disabled mode accepts no credential. */
@ConfigurationProperties("libra.analytics.core-service")
public record CoreServiceProperties(boolean enabled, String publicKey, String keyId) {
    public CoreServiceProperties {
        if (enabled && (publicKey == null || publicKey.isBlank()
                || keyId == null || !keyId.matches("[A-Za-z0-9_-]{1,64}"))) {
            throw new IllegalArgumentException("Core service authentication requires a pinned RSA public key and key ID");
        }
    }

    @Override public String toString() { return "CoreServiceProperties[enabled=" + enabled + ", key redacted]"; }
}
