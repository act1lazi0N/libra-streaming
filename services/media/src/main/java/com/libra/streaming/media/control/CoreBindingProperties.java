package com.libra.streaming.media.control;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("libra.media.core-binding")
public record CoreBindingProperties(boolean enabled, String baseUrl, String privateKey,
        String publicKey, String keyId, boolean allowHttp) {
    public CoreBindingProperties {
        if (enabled) {
            try {
                URI uri = URI.create(baseUrl);
                if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null
                        || uri.getFragment() != null || !(uri.getPath().isEmpty() || uri.getPath().equals("/"))
                        || !(uri.getScheme().equals("https") || (allowHttp && uri.getScheme().equals("http")))
                        || privateKey == null || privateKey.isBlank() || publicKey == null || publicKey.isBlank()
                        || keyId == null || !keyId.matches("[A-Za-z0-9_-]{1,64}")) {
                    throw new IllegalArgumentException();
                }
            } catch (RuntimeException exception) {
                throw new IllegalArgumentException("Core binding read requires a private HTTP(S) origin and dedicated RSA keys");
            }
        }
    }
    public URI origin() { return URI.create(baseUrl); }
    @Override public String toString() { return "CoreBindingProperties[enabled=" + enabled + ", keys redacted]"; }
}
