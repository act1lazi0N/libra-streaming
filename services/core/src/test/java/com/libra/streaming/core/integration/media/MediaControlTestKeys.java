package com.libra.streaming.core.integration.media;

import com.libra.streaming.core.TestIdentityProperties;
import com.libra.streaming.core.identity.IdentityProperties;
import com.libra.streaming.core.integration.analytics.AnalyticsProperties;
import com.libra.streaming.core.integration.security.MediaServiceProperties;
import com.libra.streaming.core.playback.PlaybackProperties;
import java.net.URI;
import java.security.KeyPair;
import java.time.Clock;
import java.util.Base64;

final class MediaControlTestKeys {
    static final KeyPair SERVICE = pair();
    static final KeyPair PLAYBACK = pair();
    static KeyPair pair() {
        try { var generator = java.security.KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); return generator.generateKeyPair(); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }
    static String privateKey(KeyPair pair) { return Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()); }
    static String publicKey(KeyPair pair) { return Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()); }
    static MediaControlProperties properties(String origin) {
        return new MediaControlProperties(true, origin, privateKey(SERVICE), publicKey(SERVICE), "core-media-test");
    }
    static IdentityProperties identity(boolean local) {
        return new IdentityProperties(TestIdentityProperties.key(), TestIdentityProperties.key(), "libra-core", "libra-web",
                URI.create("https://localhost"), local, true, "test@example.test", "", "");
    }
    static PlaybackProperties playback() { return new PlaybackProperties(privateKey(PLAYBACK), publicKey(PLAYBACK), "playback-test"); }
    static MediaControlTokens tokens(MediaControlProperties properties, boolean local, Clock clock) {
        return new MediaControlTokens(properties, identity(local), playback(), new AnalyticsProperties(false, null, "", "", ""),
                new MediaServiceProperties(false, "", ""), clock);
    }
}
