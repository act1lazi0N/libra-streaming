package com.libra.streaming.media.control;

import com.libra.streaming.media.persistence.MediaPersistenceService.Ensure;
import com.libra.streaming.media.security.CoreServiceProperties;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import jakarta.annotation.PreDestroy;
import java.net.*;
import java.net.http.*;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Reads Core's current candidate before creating a Media-owned upload. */
@Component
@EnableConfigurationProperties(CoreBindingProperties.class)
public class CoreBindingClient {
    private final CoreBindingProperties properties;
    private final Clock clock;
    private final ObjectMapper mapper;
    private final RSASSASigner signer;
    private final Semaphore inFlight = new Semaphore(16);
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(500))
            .followRedirects(HttpClient.Redirect.NEVER).proxy(new ProxySelector() {
                @Override public List<Proxy> select(URI uri) { return List.of(Proxy.NO_PROXY); }
                @Override public void connectFailed(URI uri, SocketAddress address, java.io.IOException exception) {}
            }).build();

    public CoreBindingClient(CoreBindingProperties properties, CoreServiceProperties incoming,
            Clock clock, ObjectMapper mapper) {
        this.properties = properties;
        this.clock = clock;
        this.mapper = mapper.rebuild().enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .disable(tools.jackson.databind.MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
                .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
        if (!properties.enabled()) { signer = null; return; }
        try {
            var factory = KeyFactory.getInstance("RSA");
            var privateKey = (RSAPrivateKey) factory.generatePrivate(new PKCS8EncodedKeySpec(
                    Base64.getDecoder().decode(properties.privateKey())));
            var publicKey = (RSAPublicKey) factory.generatePublic(new X509EncodedKeySpec(
                    Base64.getDecoder().decode(properties.publicKey())));
            if (publicKey.getModulus().bitLength() < 2048 || !publicKey.getModulus().equals(privateKey.getModulus())) {
                throw new IllegalArgumentException();
            }
            if (incoming.enabled()) {
                var inboundKey = (RSAPublicKey) factory.generatePublic(new X509EncodedKeySpec(
                        Base64.getDecoder().decode(incoming.publicKey())));
                if (publicKey.getModulus().equals(inboundKey.getModulus())) { throw new IllegalArgumentException(); }
            }
            var check = Signature.getInstance("SHA256withRSA");
            check.initSign(privateKey); check.update(new byte[]{1, 2, 3});
            byte[] signature = check.sign();
            check.initVerify(publicKey); check.update(new byte[]{1, 2, 3});
            if (!check.verify(signature)) { throw new IllegalArgumentException(); }
            signer = new RSASSASigner(privateKey);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Core binding read requires a matching RSA key pair of at least 2048 bits");
        }
    }

    public void requireCandidate(Ensure input) {
        if (!properties.enabled() || !inFlight.tryAcquire()) { throw new CoreBindingException("CORE_UNAVAILABLE"); }
        CompletableFuture<HttpResponse<byte[]>> pending = null;
        try {
            var now = clock.instant();
            var claims = new JWTClaimsSet.Builder().issuer("libra-media-services").subject("libra-media")
                    .audience("libra-core-internal").jwtID(UUID.randomUUID().toString())
                    .issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(60)))
                    .claim("purpose", "service-access").claim("scope", "media.bindings:read").build();
            var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256)
                    .keyID(properties.keyId()).type(com.nimbusds.jose.JOSEObjectType.JWT).build(), claims);
            jwt.sign(signer);
            var request = HttpRequest.newBuilder(properties.origin().resolve(
                    "/internal/v1/media/bindings/" + input.bindingId())).timeout(Duration.ofSeconds(2))
                    .header("Authorization", "Bearer " + jwt.serialize()).header("Accept", "application/json")
                    .GET().build();
            pending = http.sendAsync(request, ignored -> new LimitedCoreBodySubscriber());
            var response = pending.get(2, TimeUnit.SECONDS);
            if (response.statusCode() == 404) { throw new CoreBindingException("NOT_FOUND"); }
            if (response.statusCode() != 200) { throw new CoreBindingException("CORE_UNAVAILABLE"); }
            String type = response.headers().firstValue("Content-Type").orElse("").split(";", 2)[0].trim();
            if (!type.equalsIgnoreCase("application/json") || response.headers().firstValue("Content-Encoding")
                    .filter(value -> !value.equalsIgnoreCase("identity")).isPresent()) {
                throw new CoreBindingException("CORE_UNAVAILABLE");
            }
            Binding binding = mapper.readValue(response.body(), Binding.class);
            if (binding == null || !input.bindingId().equals(binding.bindingId())
                    || !input.contentId().equals(binding.contentId()) || !input.assetId().equals(binding.assetId())
                    || input.assetVersion() != binding.assetVersion() || !binding.candidate() || binding.active()) {
                throw new CoreBindingException("NOT_FOUND");
            }
        } catch (CoreBindingException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new CoreBindingException("CORE_UNAVAILABLE");
        } catch (Exception exception) {
            throw new CoreBindingException("CORE_UNAVAILABLE");
        } finally {
            if (pending != null && !pending.isDone()) { pending.cancel(true); }
            inFlight.release();
        }
    }

    @PreDestroy void close() { http.shutdownNow(); }
    record Binding(UUID bindingId, UUID contentId, UUID assetId, long assetVersion, boolean candidate, boolean active) {}
}
