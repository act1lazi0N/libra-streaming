package com.libra.streaming.media.storage;

import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class MediaStorageConfigurationTest {
    @TempDir Path scratch;

    @Test
    void missingMalformedAndIdenticalCredentialsAreRejectedWithoutEchoingValues() {
        String secret = UUID.randomUUID().toString();
        for (String access : new String[] {"", " ", "key\nvalue", "key value", "key\"value"}) {
            assertThatThrownBy(() -> properties("https://storage.example", access, secret))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        for (String badSecret : new String[] {"", "short", secret + "\n", secret + "\"", secret + " "}) {
            assertThatThrownBy(() -> properties("https://storage.example", "fixture", badSecret))
                    .isInstanceOf(IllegalArgumentException.class)
                    .satisfies(e -> assertThat(e.getMessage().contains(secret)).isFalse());
        }
        assertThatThrownBy(() -> properties("https://storage.example", secret, secret))
                .hasMessage("Media storage configuration: access and secret keys must differ");
    }

    @Test
    void endpointOriginsRejectCredentialsQueriesInvalidPortsAndImplicitCleartext() {
        for (String endpoint : new String[] {"https://user:password@storage.example", "https://storage.example?signature=x",
                "https://storage.example#x", "https://storage.example/path", "https://storage.example:0",
                "https://storage.example:65536", "http://storage.example", "file:///tmp/storage",
                "http://user:sensitive password@storage.example"}) {
            assertThatThrownBy(() -> properties(endpoint, "fixture", UUID.randomUUID().toString()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Media storage configuration: internal endpoint must be an allowed HTTP(S) origin without path or credentials");
        }
    }

    @Test
    void propertiesRepresentationDoesNotExposeIdentityOrEndpoints() {
        assertThat(properties("https://storage.example", "fixture", UUID.randomUUID().toString()).toString())
                .isEqualTo("MediaStorageProperties[credentials=REDACTED]");
    }

    private MediaStorageProperties properties(String endpoint, String access, String secret) {
        return new MediaStorageProperties(endpoint, "https://browser.example",
                "https://web.example", "us-east-1", access, secret, "libra-source", "libra-hls",
                "staging/", "sources/", "hls/", Duration.ofSeconds(1), Duration.ofSeconds(2),
                1024, scratch, false, false, false);
    }
}
