package com.libra.streaming.media.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Core's playback ticket verification key, pinned by key ID, and the cookie Core sets. {@code secureCookie} must match
 * Core's identity cookie setting: Core names the cookie {@code __Secure-LIBRA_PLAYBACK} when its cookies are Secure
 * and {@code LIBRA_PLAYBACK} only for explicit loopback development.
 */
@ConfigurationProperties("libra.media.playback")
public record PlaybackTicketProperties(boolean enabled, String publicKey, String keyId, boolean secureCookie) {
    public PlaybackTicketProperties {
        if (enabled && (publicKey == null || publicKey.isBlank()
                || keyId == null || !keyId.matches("[A-Za-z0-9_-]{1,64}"))) {
            throw new IllegalArgumentException("Playback delivery requires Core's pinned RSA public key and key ID");
        }
    }

    public String cookieName() { return secureCookie ? "__Secure-LIBRA_PLAYBACK" : "LIBRA_PLAYBACK"; }

    @Override public String toString() {
        return "PlaybackTicketProperties[enabled=" + enabled + ", secureCookie=" + secureCookie + ", key redacted]";
    }
}
