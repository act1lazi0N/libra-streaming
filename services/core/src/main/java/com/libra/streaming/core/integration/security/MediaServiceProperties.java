package com.libra.streaming.core.integration.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("libra.services.media")
public record MediaServiceProperties(boolean enabled, String publicKey, String keyId) {
    public MediaServiceProperties {
        if (enabled && (publicKey == null || publicKey.isBlank() || keyId == null || !keyId.matches("[A-Za-z0-9_-]{1,64}"))) {
            throw new IllegalArgumentException("Enabled Media service authentication requires a pinned RSA public key and key ID");
        }
    }
    @Override public String toString() { return "MediaServiceProperties[enabled=" + enabled + ", key redacted]"; }
}
