package com.libra.streaming.core.integration.analytics;

import com.libra.streaming.core.TestIdentityProperties;
import com.libra.streaming.core.identity.IdentityProperties;
import com.libra.streaming.core.playback.PlaybackProperties;
import java.net.URI;
import java.security.KeyPair;
import java.util.Base64;

final class AnalyticsTestKeys {
    static final KeyPair SERVICE = pair();
    static final KeyPair PLAYBACK = pair();
    static KeyPair pair() {
        try {
            var generator = java.security.KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048); return generator.generateKeyPair();
        } catch (java.security.GeneralSecurityException exception) { throw new IllegalStateException(exception); }
    }
    static String privateKey(KeyPair pair) { return Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()); }
    static String publicKey(KeyPair pair) { return Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()); }
    static PlaybackProperties playback() { return new PlaybackProperties(privateKey(PLAYBACK), publicKey(PLAYBACK), "playback-fixture"); }
    static AnalyticsProperties properties(URI uri) {
        return new AnalyticsProperties(true, uri, privateKey(SERVICE), publicKey(SERVICE), "analytics-fixture");
    }
    static IdentityProperties identity(boolean local) {
        return new IdentityProperties(TestIdentityProperties.key(), TestIdentityProperties.key(), "libra-core", "libra-web",
                URI.create("https://localhost"), local, true, "test@example.test", "", "");
    }
}
