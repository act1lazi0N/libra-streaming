package com.libra.streaming.media.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("libra.media.core-service")
public record CoreServiceProperties(boolean enabled, String publicKey, String keyId) {
    public CoreServiceProperties {
        if (enabled && (publicKey == null || publicKey.isBlank()
                || keyId == null || !keyId.matches("[A-Za-z0-9_-]{1,64}"))) {
            throw new IllegalArgumentException("Core service authentication requires a pinned RSA public key and key ID");
        }
    }
    @Override public String toString() { return "CoreServiceProperties[enabled=" + enabled + ", key redacted]"; }
}
