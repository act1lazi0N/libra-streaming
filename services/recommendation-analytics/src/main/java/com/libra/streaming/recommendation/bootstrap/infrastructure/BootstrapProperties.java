package com.libra.streaming.recommendation.bootstrap.infrastructure;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("libra.analytics.bootstrap")
public record BootstrapProperties(boolean enabled, URI coreBaseUrl, String privateKey, String publicKey, String keyId,
        @DefaultValue("1") long generation, @DefaultValue("200") int pageSize) {
    public BootstrapProperties {
        if (enabled && (coreBaseUrl == null || coreBaseUrl.getHost() == null
                || coreBaseUrl.getRawUserInfo() != null || coreBaseUrl.getRawQuery() != null
                || coreBaseUrl.getRawFragment() != null
                || !("http".equals(coreBaseUrl.getScheme()) || "https".equals(coreBaseUrl.getScheme()))
                || !("".equals(coreBaseUrl.getRawPath()) || "/".equals(coreBaseUrl.getRawPath()))
                || privateKey == null || privateKey.isBlank() || publicKey == null || publicKey.isBlank()
                || keyId == null || !keyId.matches("[A-Za-z0-9_-]{1,64}")
                || generation < 1 || pageSize < 1 || pageSize > 500)) {
            throw new IllegalArgumentException("Enabled bootstrap requires an HTTP(S) origin, dedicated keys, key ID and valid generation/page size");
        }
    }

    @Override
    public String toString() { return "BootstrapProperties[enabled=" + enabled + ", keys redacted]"; }
}
