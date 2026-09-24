package com.libra.streaming.core.integration.media;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Separate from the inbound Media-to-Core identity. */
@ConfigurationProperties("libra.media-control")
public record MediaControlProperties(boolean enabled, String baseUrl, String privateKey, String publicKey, String keyId) {
    public MediaControlProperties {
        if (enabled) {
            try {
                URI origin = URI.create(baseUrl);
                if (origin.getHost() == null || origin.getUserInfo() != null || origin.getQuery() != null
                        || origin.getFragment() != null || !(origin.getPath().isEmpty() || origin.getPath().equals("/"))
                        || !("https".equals(origin.getScheme()) || "http".equals(origin.getScheme()))
                        || privateKey == null || privateKey.isBlank() || publicKey == null || publicKey.isBlank()
                        || keyId == null || !keyId.matches("[A-Za-z0-9_-]{1,64}")) {
                    throw new IllegalArgumentException();
                }
            } catch (RuntimeException exception) {
                throw new IllegalArgumentException("Media control requires an HTTP(S) origin and dedicated RSA keys/key ID");
            }
        }
    }

    public URI origin() { return URI.create(baseUrl); }
    @Override public String toString() { return "MediaControlProperties[enabled=" + enabled + ", configuration redacted]"; }
}
