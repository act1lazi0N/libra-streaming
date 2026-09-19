package com.libra.streaming.core.playback;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("libra.playback")
public record PlaybackProperties(String privateKey, String publicKey, String keyId) {
    public PlaybackProperties {
        if (privateKey == null || privateKey.isBlank() || publicKey == null || publicKey.isBlank()
                || keyId == null || !keyId.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("Playback RSA keys and a safe key ID are required");
        }
    }

    @Override public String toString() { return "PlaybackProperties[secrets redacted]"; }
}
