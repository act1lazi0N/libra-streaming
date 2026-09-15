package com.libra.streaming.core;

import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.test.context.DynamicPropertyRegistry;

public final class TestIdentityProperties {
    private static final String JWT = key();
    private static final String MAIL = key();

    private TestIdentityProperties() {}

    public static void register(DynamicPropertyRegistry registry) {
        registry.add("libra.catalog.media-listener-enabled", () -> false);
        registry.add("libra.identity.jwt-key", () -> JWT);
        registry.add("libra.identity.mail-key", () -> MAIL);
        registry.add("libra.identity.local-development", () -> true);
        registry.add("libra.identity.cookie-secure", () -> false);
        registry.add("libra.identity.public-base-url", () -> "http://localhost:3000");
        registry.add("libra.identity.mail-worker-enabled", () -> false);
        registry.add("spring.mail.properties.mail.smtp.starttls.enable", () -> false);
        registry.add("spring.mail.properties.mail.smtp.starttls.required", () -> false);
    }

    public static String key() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }
}
