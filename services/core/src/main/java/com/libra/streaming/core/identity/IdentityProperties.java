package com.libra.streaming.core.identity;

import java.net.URI;
import java.util.Base64;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("libra.identity")
public record IdentityProperties(String jwtKey, String mailKey, String issuer, String audience,
        URI publicBaseUrl, boolean localDevelopment, boolean cookieSecure, String mailFrom,
        String bootstrapEmail, String bootstrapPassword) {
    public IdentityProperties {
        validateKey(jwtKey, "JWT");
        validateKey(mailKey, "mail encryption");
        if (jwtKey.equals(mailKey)) {
            throw new IllegalArgumentException("JWT and mail encryption keys must be different");
        }
        if (issuer == null || issuer.isBlank() || audience == null || audience.isBlank()) {
            throw new IllegalArgumentException("Identity issuer and audience are required");
        }
        if (!localDevelopment && !cookieSecure) {
            throw new IllegalArgumentException("Secure identity cookies are required outside local development");
        }
        if (publicBaseUrl == null || publicBaseUrl.getHost() == null
                || publicBaseUrl.getUserInfo() != null || publicBaseUrl.getQuery() != null
                || publicBaseUrl.getFragment() != null
                || !("https".equals(publicBaseUrl.getScheme()) || (localDevelopment
                    && "http".equals(publicBaseUrl.getScheme())
                    && java.util.Set.of("localhost", "127.0.0.1", "[::1]").contains(publicBaseUrl.getHost())))) {
            throw new IllegalArgumentException("Public URL must use HTTPS (loopback HTTP allowed for local development)");
        }
        if (mailFrom == null || !mailFrom.matches("[^\\s@]+@[^\\s@]+")) {
            throw new IllegalArgumentException("A valid sender address is required");
        }
        bootstrapEmail = bootstrapEmail == null ? "" : bootstrapEmail;
        bootstrapPassword = bootstrapPassword == null ? "" : bootstrapPassword;
        if (bootstrapEmail.isBlank() != bootstrapPassword.isBlank()) {
            throw new IllegalArgumentException("Configure both bootstrap administrator credentials or neither");
        }
    }

    private static void validateKey(String value, String purpose) {
        try {
            if (value == null || Base64.getDecoder().decode(value).length != 32) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("A Base64-encoded random 32-byte " + purpose + " key is required");
        }
    }

    @Override
    public String toString() {
        return "IdentityProperties[secrets redacted]";
    }
}
