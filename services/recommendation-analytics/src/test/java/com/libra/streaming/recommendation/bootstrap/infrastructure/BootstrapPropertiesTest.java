package com.libra.streaming.recommendation.bootstrap.infrastructure;

import java.net.URI;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import static org.assertj.core.api.Assertions.*;

class BootstrapPropertiesTest {
    @Test
    void validOriginsAndBoundaryValues() {
        for (String origin : new String[] {"http://core:8081", "https://core/"}) {
            for (int size : new int[] {1, 200, 500}) {
                assertThat(config(origin, "private-canary", "public-canary", "a_Z-9", 1, size).pageSize()).isEqualTo(size);
            }
        }
    }

    @Test
    void bindingSuppliesDefaultsAndDisabledModeNeedsNoCredentials() {
        var source = new MapConfigurationPropertySource(java.util.Map.of("libra.analytics.bootstrap.enabled", "false"));
        var value = new Binder(source).bind("libra.analytics.bootstrap", Bindable.of(BootstrapProperties.class)).get();
        assertThat(value.generation()).isEqualTo(1);
        assertThat(value.pageSize()).isEqualTo(200);
        assertThat(new BootstrapProperties(false, null, "", "", "", 1, 200).enabled()).isFalse();
    }

    @Test
    void diagnosticsRedactBothKeys() {
        assertThat(config("http://core", "private-canary", "public-canary", "kid", 1, 200).toString())
                .doesNotContain("private-canary", "public-canary");
    }

    static Stream<Arguments> invalid() {
        var cases = new java.util.ArrayList<Arguments>();
        for (String uri : new String[] {null, "", "ftp://core", "/relative", "http://user@core", "http://core?x=1",
                "http://core#fragment", "http://core/path", "http://core/%2f"}) {
            cases.add(Arguments.of(uri, "private-canary", "public-canary", "kid", 1L, 200));
        }
        for (String blank : new String[] {null, "", " "}) {
            cases.add(Arguments.of("http://core", blank, "public-canary", "kid", 1L, 200));
            cases.add(Arguments.of("http://core", "private-canary", blank, "kid", 1L, 200));
        }
        for (String kid : new String[] {null, "", " ", "bad.kid", "a".repeat(65)}) {
            cases.add(Arguments.of("http://core", "private-canary", "public-canary", kid, 1L, 200));
        }
        cases.add(Arguments.of("http://core", "private-canary", "public-canary", "kid", 0L, 200));
        cases.add(Arguments.of("http://core", "private-canary", "public-canary", "kid", -1L, 200));
        for (int size : new int[] {-1, 0, 501}) {
            cases.add(Arguments.of("http://core", "private-canary", "public-canary", "kid", 1L, size));
        }
        return cases.stream();
    }

    @ParameterizedTest
    @MethodSource("invalid")
    void rejectsEveryInvalidBoundary(String uri, String privateKey, String publicKey, String kid, long generation, int size) {
        assertThatThrownBy(() -> config(uri, privateKey, publicKey, kid, generation, size))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Enabled bootstrap requires an HTTP(S) origin, dedicated keys, key ID and valid generation/page size");
    }

    private static BootstrapProperties config(String uri, String privateKey, String publicKey, String kid, long gen, int size) {
        return new BootstrapProperties(true, uri == null ? null : URI.create(uri), privateKey, publicKey, kid, gen, size);
    }
}
