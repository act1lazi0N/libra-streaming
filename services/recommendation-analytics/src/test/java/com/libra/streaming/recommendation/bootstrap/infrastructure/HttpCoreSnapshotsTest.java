package com.libra.streaming.recommendation.bootstrap.infrastructure;

import com.libra.streaming.recommendation.bootstrap.application.CoreSnapshots.SnapshotUnavailableException;
import com.sun.net.httpserver.HttpServer;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.SignedJWT;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HttpCoreSnapshotsTest {
    static final String ID = "00000000-0000-0000-0000-000000000001";
    static final String ID2 = "00000000-0000-0000-0000-000000000002";
    static final String CURSOR = "123." + ID;
    static final JsonMapper JSON = JsonMapper.builder().build();
    HttpServer server;
    ExecutorService executor;
    HttpCoreSnapshots client;
    final List<Request> requests = new CopyOnWriteArrayList<>();
    volatile int status = 200;
    volatile String type = "application/json", body = "{\"items\":[],\"next\":null}";
    volatile long delay;

    record Request(String method, String path, String query, String accept, String authorization) {}

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            requests.add(new Request(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    exchange.getRequestURI().getRawQuery(), exchange.getRequestHeaders().getFirst("Accept"),
                    exchange.getRequestHeaders().getFirst("Authorization")));
            try (exchange) {
                if (delay > 0) {
                    try { Thread.sleep(delay); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
                }
                if (type != null) { exchange.getResponseHeaders().set("Content-Type", type); }
                exchange.getResponseHeaders().set("Location", "/redirect-target");
                exchange.sendResponseHeaders(status, 0); // Chunked: the body limit must apply while reading.
                exchange.getResponseBody().write(body.getBytes(StandardCharsets.UTF_8));
            } catch (java.io.IOException cancelled) { /* A timeout or size violation cancels the connection. */ }
        });
        server.start();
        var properties = BootstrapTokensTest.properties(BootstrapTokensTest.KEY, BootstrapTokensTest.KEY,
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
        client = new HttpCoreSnapshots(properties, BootstrapTokensTest.tokens(properties), BootstrapTokensTest.CLOCK,
                Duration.ofSeconds(1), Duration.ofSeconds(5));
    }

    @AfterEach
    void stop() {
        if (client != null) { client.close(); }
        if (server != null) { server.stop(0); }
        if (executor != null) { executor.shutdownNow(); }
    }

    @Test
    void sendsCorrectPathsQueriesAndFreshVerifiableCredentialsOnEveryCall() throws Exception {
        client.profiles(null, 2);
        client.profiles(UUID.fromString(ID), 3);
        client.catalog(null, 4);
        client.catalog(UUID.fromString(ID), 5);
        body = "{\"events\":[],\"next\":null}";
        client.playback(null, 6);
        client.playback(CURSOR, 7);
        assertThat(requests).extracting(Request::path).containsExactly(
                "/internal/v1/analytics/snapshots/profiles", "/internal/v1/analytics/snapshots/profiles",
                "/internal/v1/analytics/snapshots/catalog", "/internal/v1/analytics/snapshots/catalog",
                "/internal/v1/analytics/events/playback", "/internal/v1/analytics/events/playback");
        assertThat(requests).extracting(Request::query).containsExactly("limit=2", "after=" + ID + "&limit=3",
                "limit=4", "after=" + ID + "&limit=5", "limit=6", "after=" + CURSOR + "&limit=7");
        var ids = new java.util.HashSet<String>();
        for (Request request : requests) {
            assertThat(request.method()).isEqualTo("GET");
            assertThat(request.accept()).isEqualTo("application/json");
            assertThat(request.authorization()).startsWith("Bearer ");
            var jwt = SignedJWT.parse(request.authorization().substring(7));
            assertThat(jwt.verify(new RSASSAVerifier((RSAPublicKey) BootstrapTokensTest.KEY.getPublic()))).isTrue();
            assertThat(jwt.getJWTClaimsSet().getStringClaim("scope")).isEqualTo("analytics.bootstrap:read");
            assertThat(ids.add(jwt.getJWTClaimsSet().getJWTID())).isTrue();
        }
    }

    @Test
    void parsesProfilesAndAcceptsUtf8Charset() {
        type = "application/json;charset=utf-8";
        body = page(profile(ID), "\"" + ID + "\"");
        var page = client.profiles(null, 1);
        assertThat(page.next()).isEqualTo(UUID.fromString(ID));
        assertThat(page.items()).singleElement().satisfies(item -> {
            assertThat(item.profileId()).isEqualTo(UUID.fromString(ID));
            assertThat(item.accountId()).isEqualTo(UUID.fromString(ID2));
            assertThat(item.version()).isEqualTo(7);
            assertThat(item.deleted()).isTrue();
        });
    }

    @Test
    void parsesPublishedCatalogWithSharedMetadataAndBindingRules() {
        body = page(catalog(), "null");
        var node = client.catalog(null, 2).items().getFirst();
        assertThat(node.contentId()).isEqualTo(UUID.fromString(ID));
        assertThat(node.version()).isEqualTo(3);
        assertThat(node.published().metadata().title()).isEqualTo("A film");
        assertThat(node.published().revision()).isEqualTo(2);
        assertThat(node.active().durationSeconds()).isEqualTo(120);
        assertThat(node.publishedAt()).isEqualTo(java.time.Instant.parse("2026-01-02T03:04:05Z"));
        assertThat(node.inheritsPublishedAt()).isFalse();
        assertThat(node.changedAt()).isEqualTo(BootstrapTokensTest.CLOCK.instant());
    }

    @Test
    void replaysObjectsWithoutValidatingTheirPayloads() {
        String event = "{\"aggregateId\":\"arbitrary-key\",\"payload\":\"invalid-for-intake\",\"extra\":true}";
        body = "{\"events\":[" + event + "],\"next\":\"" + CURSOR + "\"}";
        var page = client.playback(null, 1);
        assertThat(page.next()).isEqualTo(CURSOR);
        assertThat(page.events()).singleElement().satisfies(item -> {
            assertThat(item.key()).isEqualTo("arbitrary-key");
            assertThat(JSON.readTree(item.json())).isEqualTo(JSON.readTree(event));
        });
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 500, 301, 302, 307, 308})
    void rejectsStatusAndDoesNotFollowRedirects(int code) {
        status = code;
        body = "private-body-canary";
        fails("CORE_STATUS", () -> client.profiles(UUID.fromString(ID), 2));
        assertThat(requests).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"text/plain", "application/problem+json", "application/json;charset=latin1", "application/json;x=y"})
    void rejectsWrongContentType(String contentType) {
        type = contentType;
        fails("CORE_BODY", () -> client.profiles(null, 2));
    }

    @Test
    void rejectsMissingContentType() {
        type = null;
        fails("CORE_BODY", () -> client.profiles(null, 2));
    }

    @Test
    void rejectsOversizedChunkedBody() {
        body = " ".repeat(4 * 1024 * 1024) + "private-body-canary";
        fails("CORE_BODY", () -> client.profiles(UUID.fromString(ID), 2));
    }

    static Stream<String> invalidProfiles() {
        return Stream.of("private-body-canary", "null", "[]", "{}", "{\"items\":[],\"next\":null,\"next\":null}",
                "{\"items\":[],\"next\":null,\"unknown\":1}", "{\"items\":{},\"next\":null}",
                page("null", "null"), page(profile(ID).replace("true", "\"true\""), "null"),
                page(profile(ID).replace("7", "7.0"), "null"), page(profile(ID).replace("7", "0"), "null"),
                page(profile(ID).replace(ID2, "bad-uuid"), "null"), page(profile(ID).replace("\"deleted\":true", "\"extra\":true"), "null"),
                page(profile(ID2) + "," + profile(ID), "null"), page(profile(ID) + "," + profile(ID), "null"),
                page("", "\"" + ID + "\""), page(profile(ID), "\"" + ID2 + "\""),
                page(profile(ID), "17"), page(profile(ID), "null") + "{}",
                page(profile(ID) + "," + profile(ID2) + "," + profile("00000000-0000-0000-0000-000000000003"), "null"));
    }

    @ParameterizedTest
    @MethodSource("invalidProfiles")
    void rejectsMalformedDuplicateOpenOrContractInvalidProfiles(String invalid) {
        body = invalid;
        fails("CORE_BODY", () -> client.profiles(null, 2));
    }

    @Test
    void rejectsIdsAtOrBeforeAfterAndNextOnShortPages() {
        body = page(profile(ID), "null");
        fails("CORE_BODY", () -> client.profiles(UUID.fromString(ID), 2));
        fails("CORE_BODY", () -> client.profiles(UUID.fromString(ID2), 2));
        body = page(profile(ID), "\"" + ID + "\"");
        fails("CORE_BODY", () -> client.profiles(null, 2));
    }

    static Stream<String> invalidCatalogs() {
        return Stream.of(catalog().replace("\"kind\":\"MOVIE\"", "\"kind\":\"UNKNOWN\""),
                catalog().replace("\"parentId\":null", "\"parentId\":\"" + ID2 + "\""),
                catalog().replace("\"ordinal\":null", "\"ordinal\":1"),
                catalog().replace("\"title\":\"A film\"", "\"title\":17"),
                catalog().replace("\"language\":\"en\"", "\"language\":\"invalid-language\""),
                catalog().replace("\"state\":\"READY\"", "\"state\":\"UNKNOWN\""),
                catalog().replace("\"durationSeconds\":120", "\"durationSeconds\":0"),
                catalog().replace("\"contentId\":\"" + ID + "\",\"assetId\"", "\"contentId\":\"" + ID2 + "\",\"assetId\""),
                catalog().replace("2026-01-02T03:04:05Z", "not-a-date"),
                catalog().replace("\"accessTier\":\"FREE\"", "\"accessTier\":\"FREE\",\"unknown\":true"));
    }

    @ParameterizedTest
    @MethodSource("invalidCatalogs")
    void rejectsCatalogInvariantAndNestedContractViolations(String item) {
        body = page(item, "null");
        fails("CORE_BODY", () -> client.catalog(null, 2));
    }

    @Test
    void catalogOrderingCursorAndUnpublishedShapeAreChecked() {
        body = page(catalog() + "," + catalog(), "null");
        fails("CORE_BODY", () -> client.catalog(null, 2));
        body = page(catalog(), "null");
        fails("CORE_BODY", () -> client.catalog(UUID.fromString(ID), 2));
        body = page(catalog(), "\"" + ID2 + "\"");
        fails("CORE_BODY", () -> client.catalog(null, 1));
        ObjectNode item = (ObjectNode) JSON.readTree(catalog());
        item.putNull("published");
        body = page(item.toString(), "null");
        fails("CORE_BODY", () -> client.catalog(null, 2));
        item.putNull("active");
        item.putNull("publishedAt");
        body = page(item.toString(), "null");
        assertThat(client.catalog(null, 2).items().getFirst().isPublished()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"events\":[17],\"next\":null}", "{\"events\":[{}],\"next\":null}",
            "{\"events\":[{\"aggregateId\":17}],\"next\":null}",
            "{\"events\":[],\"next\":\"bad-cursor\"}", "{\"events\":[],\"next\":null,\"extra\":true}"})
    void rejectsInvalidReplayPageShape(String invalid) {
        body = invalid;
        fails("CORE_BODY", () -> client.playback(null, 1));
    }

    @Test
    void rejectsReplayCursorPatternAndNextOnShortPage() {
        body = "{\"events\":[{\"aggregateId\":\"key\"}],\"next\":\"bad-cursor\"}";
        fails("CORE_BODY", () -> client.playback(null, 1));
        body = "{\"events\":[{\"aggregateId\":\"key\"}],\"next\":\"" + CURSOR + "\"}";
        fails("CORE_BODY", () -> client.playback(null, 2));
    }

    @Test
    void slowResponseExceedingBudgetIsTransportFailure() {
        client.close();
        var properties = BootstrapTokensTest.properties(BootstrapTokensTest.KEY, BootstrapTokensTest.KEY,
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
        client = new HttpCoreSnapshots(properties, BootstrapTokensTest.tokens(properties), BootstrapTokensTest.CLOCK,
                Duration.ofSeconds(1), Duration.ofMillis(100));
        delay = 1000;
        fails("CORE_TRANSPORT", () -> client.profiles(UUID.fromString(ID), 2));
    }

    void fails(String code, Supplier<?> call) {
        var failure = assertThrows(SnapshotUnavailableException.class, call::get);
        assertThat(failure).hasMessage(code).hasCause(null);
        assertThat(failure.getMessage()).doesNotContain("private-body-canary", ID, CURSOR, "after=", "limit=");
        for (Request request : requests) {
            if (request.authorization() != null) {
                assertThat(failure.getMessage()).doesNotContain(request.authorization(), request.authorization().substring(7));
            }
        }
    }

    static String page(String items, String next) { return "{\"items\":[" + items + "],\"next\":" + next + "}"; }
    static String profile(String id) {
        return "{\"profileId\":\"" + id + "\",\"accountId\":\"" + ID2 + "\",\"version\":7,\"deleted\":true}";
    }
    static String catalog() {
        return """
                {"contentId":"%s","version":3,"kind":"MOVIE","parentId":null,"ordinal":null,
                 "published":{"revision":2,"createdAt":"2026-01-01T00:00:00Z","metadata":{
                  "title":"A film","description":"","genres":["drama"],"releaseYear":2026,"language":"en",
                  "credits":[],"imageReferences":[],"accessTier":"FREE"}},
                 "active":{"id":"%s","contentId":"%s","assetId":"%s","assetVersion":1,
                  "projectionVersion":0,"state":"READY","durationSeconds":120},"publishedAt":"2026-01-02T03:04:05Z"}
                """.formatted(ID, ID2, ID, ID2);
    }
}
