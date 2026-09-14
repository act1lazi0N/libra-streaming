package com.libra.streaming.core.identity;

import java.net.URI;
import java.util.UUID;
import com.libra.streaming.core.TestIdentityProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.*;

class IdentityPolicyTest {
    private IdentityProperties properties() {
        return new IdentityProperties(TestIdentityProperties.key(), TestIdentityProperties.key(), "issuer", "audience",
                URI.create("https://libra.example"), false, true, "libra@example.test", "", "");
    }

    @Test
    void missingOrReusedKeysAndInsecureProductionCookiesFailClosed() {
        assertThatThrownBy(() -> new IdentityProperties("", "", "issuer", "audience", URI.create("https://libra.example"),
                false, true, "libra@example.test", "", "")).isInstanceOf(IllegalArgumentException.class);
        String key = TestIdentityProperties.key();
        assertThatThrownBy(() -> new IdentityProperties(key, key, "issuer", "audience", URI.create("https://libra.example"),
                false, true, "libra@example.test", "", "")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IdentityProperties(key, TestIdentityProperties.key(), "issuer", "audience", URI.create("https://libra.example"),
                false, false, "libra@example.test", "", "")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IdentityProperties(key, TestIdentityProperties.key(), "issuer", "audience", URI.create("http://libra.example"),
                true, false, "libra@example.test", "", "")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void encryptedMailIsBoundToItsRowAndAuthenticatesCiphertext() {
        var secrets = new IdentitySecrets(properties());
        UUID id = UUID.randomUUID();
        String encrypted = secrets.encrypt(id, "synthetic payload");
        assertThat(secrets.decrypt(id, encrypted)).isEqualTo("synthetic payload");
        assertThatThrownBy(() -> secrets.decrypt(UUID.randomUUID(), encrypted)).isInstanceOf(IllegalStateException.class);
        byte[] bytes = java.util.Base64.getDecoder().decode(encrypted);
        bytes[bytes.length - 1] ^= 1;
        assertThatThrownBy(() -> secrets.decrypt(id, java.util.Base64.getEncoder().encodeToString(bytes))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void productionCookiesUseHostPrefixSecureHttpOnlyAndHostWidePath() {
        var cookies = new IdentityCookies(properties(), java.time.Clock.systemUTC());
        var response = new MockHttpServletResponse();
        cookies.clear(response);
        assertThat(response.getHeaders("Set-Cookie")).hasSize(3).allSatisfy(header ->
                assertThat(header).contains("__Host-LIBRA_", "Secure", "HttpOnly", "Path=/", "SameSite=Lax", "Max-Age=0")
                        .doesNotContain("Domain="));
    }

    @Test
    void passwordPolicyCountsUnicodeWithoutSilentTruncation() {
        IdentityAccountService.validatePassword("🙂".repeat(128));
        assertThatThrownBy(() -> IdentityAccountService.validatePassword("🙂".repeat(129))).isInstanceOf(IdentityException.class);
        assertThatThrownBy(() -> IdentityAccountService.validatePassword("x".repeat(11))).isInstanceOf(IdentityException.class);
        IdentityAccountService.validatePassword("x".repeat(12));
    }
}
