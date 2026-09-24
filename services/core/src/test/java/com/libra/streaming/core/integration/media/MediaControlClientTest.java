package com.libra.streaming.core.integration.media;

import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static com.libra.streaming.core.integration.media.MediaControlModels.*;
import static com.libra.streaming.core.integration.media.MediaControlTestKeys.*;

class MediaControlClientTest {
    HttpServer server;
    ExecutorService executor;
    ValidatorFactory validators;
    MediaControlClient client;
    ObjectMapper mapper = new ObjectMapper();
    UUID upload = UUID.randomUUID();
    EnsureUpload intent = new EnsureUpload(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            1, 1024, "a".repeat(64), Instant.now().plusSeconds(3600).truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
    volatile Responder responder;
    volatile String authorization;
    volatile String method;
    volatile String requestBody;
    volatile String cookie;
    AtomicInteger calls = new AtomicInteger();

    @BeforeEach void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor(); server.setExecutor(executor);
        server.createContext("/", exchange -> {
            calls.incrementAndGet(); authorization = exchange.getRequestHeaders().getFirst("Authorization");
            method = exchange.getRequestMethod(); cookie = exchange.getRequestHeaders().getFirst("Cookie");
            requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            try { responder.respond(exchange); } catch (Exception exception) { exchange.close(); }
        });
        responder = exchange -> respond(exchange, 200, "application/json", statusBody());
        server.start(); validators = Validation.buildDefaultValidatorFactory();
        var properties = properties("http://127.0.0.1:" + server.getAddress().getPort());
        client = new MediaControlClient(properties, tokens(properties, true, Clock.systemUTC()), mapper, validators.getValidator());
    }
    @AfterEach void cleanup() { client.close(); server.stop(0); executor.shutdownNow(); validators.close(); }

    @Test void sendsTypedContextAndOperationSpecificCredentialWithoutCookies() throws Exception {
        assertThat(client.read(upload, UUID.randomUUID()).failure()).isNull();
        assertThat(method).isEqualTo("GET"); assertThat(requestBody).isEmpty(); assertThat(cookie).isNull();
        assertThat(SignedJWT.parse(authorization.substring(7)).getJWTClaimsSet().getStringClaim("scope"))
                .isEqualTo("core.media.uploads:read");
        responder = exchange -> respond(exchange, 201, "application/json", statusBody());
        assertThat(client.ensure(upload, intent, UUID.randomUUID()).failure()).isNull();
        assertThat(method).isEqualTo("PUT");
        assertThat(SignedJWT.parse(authorization.substring(7)).getJWTClaimsSet().getStringClaim("scope"))
                .isEqualTo("core.media.uploads:write");
        assertThat(mapper.readTree(requestBody).get("bindingId").asString()).isEqualTo(intent.bindingId().toString());
        assertThat(requestBody).doesNotContain("role", "cookie", "storageKey", "Authorization");
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test void translatesRemoteFailuresWithoutRetryOrBodyDisclosure() {
        int[] statuses = {401, 403, 404, 409, 410, 500, 503};
        Failure[] failures = {Failure.ACCESS_DENIED, Failure.ACCESS_DENIED, Failure.NOT_FOUND, Failure.CONFLICT,
                Failure.EXPIRED, Failure.UNAVAILABLE, Failure.UNAVAILABLE};
        for (int i = 0; i < statuses.length; i++) {
            int code = statuses[i]; responder = exchange -> respond(exchange, code, "text/plain", "canary-private-storage-error");
            Result result = client.ensure(upload, intent, UUID.randomUUID());
            assertThat(result.failure()).isEqualTo(failures[i]); assertThat(result.value()).isNull();
            assertThat(result.toString()).doesNotContain("canary");
        }
        assertThat(calls.get()).isEqualTo(statuses.length);
    }

    @Test void rejectsRedirectsWithoutFollowingThem() {
        responder = exchange -> {
            exchange.getResponseHeaders().add("Location", "/unexpected"); respond(exchange, 307, "text/plain", "redirect");
        };
        assertThat(client.ensure(upload, intent, UUID.randomUUID()).failure()).isEqualTo(Failure.UNAVAILABLE);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test void boundsSlowHeadersAndSlowBodiesIncludingChunkedResponses() {
        for (boolean headersFirst : new boolean[] {false, true}) {
            responder = exchange -> {
                if (headersFirst) { exchange.getResponseHeaders().add("Content-Type", "application/json"); exchange.sendResponseHeaders(200, 0); }
                Thread.sleep(10000);
            };
            long start = System.nanoTime();
            assertThat(client.read(upload, UUID.randomUUID()).failure()).isEqualTo(Failure.UNAVAILABLE);
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(4));
        }
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test void rejectsOversizedOrUnexpectedResponsesAndIdentityMismatch() throws Exception {
        for (String body : new String[] {"x".repeat(65537), "{bad-json", "null", "{}",
                statusBody().replace(upload.toString(), UUID.randomUUID().toString()),
                statusBody().replace("UPLOADING", "READY"), statusBody().replace("\"attemptCount\":0", "\"attemptCount\":1.5"),
                statusBody().replace("\"assetVersion\":1", "\"assetVersion\":\"1\""), statusBody() + " {}"}) {
            responder = exchange -> respond(exchange, 200, "application/json", body);
            assertThat(client.read(upload, UUID.randomUUID()).failure()).isIn(Failure.INVALID_RESPONSE, Failure.UNAVAILABLE);
        }
        responder = exchange -> respond(exchange, 200, "text/html", statusBody());
        assertThat(client.read(upload, UUID.randomUUID()).failure()).isEqualTo(Failure.INVALID_RESPONSE);
        responder = exchange -> respond(exchange, 200, "application/json", statusBody().replace(intent.bindingId().toString(), UUID.randomUUID().toString()));
        assertThat(client.ensure(upload, intent, UUID.randomUUID()).failure()).isEqualTo(Failure.INVALID_RESPONSE);
    }

    @Test void disabledClientAndInvalidContextNeverContactMedia() {
        var disabled = new MediaControlProperties(false, "", "", "", "");
        var disabledClient = new MediaControlClient(disabled, tokens(disabled, false, Clock.systemUTC()), mapper, validators.getValidator());
        try { assertThat(disabledClient.read(upload, UUID.randomUUID()).failure()).isEqualTo(Failure.DISABLED); }
        finally { disabledClient.close(); }
        assertThatThrownBy(() -> client.ensure(upload, null, UUID.randomUUID())).isInstanceOf(IllegalArgumentException.class);
        assertThat(calls.get()).isZero();
    }

    String statusBody() {
        return mapper.writeValueAsString(new Status(upload, intent.contentId(), intent.bindingId(), intent.assetId(), 1,
                UploadState.OPEN, AssetState.UPLOADING, null, 0, null, intent.expiresAt()));
    }
    static void respond(HttpExchange exchange, int status, String type, String body) throws Exception {
        exchange.getResponseHeaders().add("Content-Type", type);
        exchange.sendResponseHeaders(status, 0);
        try (var out = exchange.getResponseBody()) { out.write(body.getBytes(StandardCharsets.UTF_8)); }
    }
    interface Responder { void respond(HttpExchange exchange) throws Exception; }
}
