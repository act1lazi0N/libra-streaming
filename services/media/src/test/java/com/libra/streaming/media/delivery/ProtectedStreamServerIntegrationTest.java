package com.libra.streaming.media.delivery;

import com.libra.streaming.media.delivery.application.StreamObjects;
import com.libra.streaming.media.processing.application.OutputFinalizer;
import com.libra.streaming.media.processing.infrastructure.FinalizationFixture;
import com.libra.streaming.media.security.CoreTicketFixture;
import com.libra.streaming.media.storage.S3StreamObjects;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import static org.assertj.core.api.Assertions.*;

/**
 * Protected delivery over a real socket to Media's embedded Tomcat (M30): what the container itself writes when a
 * stream fails before its first byte, how path spellings Tomcat and Spring normalise are answered, caching and
 * conditional headers, a ticket expiring between requests, a missing object, and players that hang up. The READY
 * generation, PostgreSQL, SeaweedFS and ffmpeg are real; storage faults are injected around the real
 * {@link S3StreamObjects}. Requests are written byte for byte so no client library normalises a path or a header.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        // The storage settings of SourceStorageFixture, whose @SpringBootTest this annotation replaces.
        "libra.media.storage.enabled=true",
        "libra.media.storage.region=us-east-1",
        "libra.media.storage.source-bucket=libra-source",
        "libra.media.storage.hls-bucket=libra-hls",
        "libra.media.storage.staging-prefix=staging/",
        "libra.media.storage.source-prefix=sources/",
        "libra.media.storage.hls-prefix=hls/",
        "libra.media.storage.connect-timeout=3s",
        "libra.media.storage.request-timeout=15s",
        "libra.media.storage.max-object-bytes=1048576",
        "libra.media.storage.allow-http=true",
        "libra.media.storage.initialize-buckets=true",
        "libra.media.storage.probe-on-startup=false"
})
@DirtiesContext
class ProtectedStreamServerIntegrationTest extends FinalizationFixture {
    static final CoreTicketFixture CORE = new CoreTicketFixture();
    static final String COOKIE = "__Secure-LIBRA_PLAYBACK";

    /** What the storage around the real objects does to the next opened stream. */
    enum Fault { NONE, FAIL_FIRST_READ, FAIL_AFTER_ONE_BUFFERED_BYTE, SLOW_OPEN, HOLD_AFTER_FIRST_CHUNK, UNEXPECTED }

    static volatile Fault fault = Fault.NONE;
    static volatile Instant openNotBefore = Instant.EPOCH;
    static volatile CountDownLatch held = new CountDownLatch(0);
    static volatile CountDownLatch release = new CountDownLatch(0);

    @DynamicPropertySource
    static void playback(DynamicPropertyRegistry registry) {
        registry.add("libra.media.playback.enabled", () -> true);
        registry.add("libra.media.playback.public-key", CORE::publicKey);
        registry.add("libra.media.playback.key-id", () -> CoreTicketFixture.KID);
        registry.add("libra.media.playback.secure-cookie", () -> true);
        registry.add("libra.media.delivery.max-streams-per-session", () -> 4);
        registry.add("libra.media.delivery.max-streams", () -> 8);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FaultyStorage {
        @Bean @Primary
        StreamObjects faultyStreamObjects(S3StreamObjects real) {
            return (key, maxBytes) -> {
                var current = fault;
                if (current == Fault.SLOW_OPEN) { sleepUntil(openNotBefore); }
                // A defect, not a storage failure: nothing maps it, so the container would answer it.
                if (current == Fault.UNEXPECTED) { throw new IllegalStateException("SELECT secret FROM hls/x"); }
                return real.open(key, maxBytes).map(opened -> faulty(opened, current));
            };
        }

        private static StreamObjects.Opened faulty(StreamObjects.Opened opened, Fault current) {
            return new StreamObjects.Opened() {
                @Override public long length() { return opened.length(); }

                @Override public void transferTo(OutputStream sink) throws IOException {
                    switch (current) {
                        case FAIL_FIRST_READ -> throw new StreamObjects.Unavailable();
                        case FAIL_AFTER_ONE_BUFFERED_BYTE -> {
                            sink.write(0x47);
                            throw new StreamObjects.Unavailable();
                        }
                        case HOLD_AFTER_FIRST_CHUNK -> opened.transferTo(new OutputStream() {
                            boolean first = true;
                            @Override public void write(int value) throws IOException {
                                write(new byte[]{(byte) value});
                            }
                            @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                                sink.write(bytes, offset, length);
                                if (first) {
                                    first = false;
                                    sink.flush();
                                    held.countDown();
                                    await(release);
                                }
                            }
                        });
                        default -> opened.transferTo(sink);
                    }
                }

                @Override public void close() { opened.close(); }
            };
        }
    }

    @LocalServerPort int port;
    @Autowired S3Client s3;

    @AfterEach void heal() {
        fault = Fault.NONE;
        release.countDown();
    }

    record Selection(UUID content, UUID binding, UUID asset, long version, String prefix) {}

    Selection ready(String fixture) throws Exception {
        assertThat(finalizer.complete(stagedClaim(fixture), () -> false))
                .isInstanceOf(OutputFinalizer.Result.Ready.class);
        Map<String, Object> row = asset();
        return new Selection((UUID) row.get("content_id"), (UUID) row.get("binding_id"), (UUID) row.get("asset_id"),
                (Long) row.get("asset_version"), (String) row.get("output_prefix"));
    }

    /** A ticket Core issued at {@code issued}; it expires five minutes later, as Core's always do. */
    String ticket(UUID session, Selection selection, Instant issued) {
        return CORE.ticket(session, selection.content(), selection.binding(), selection.asset(), selection.version(),
                issued.truncatedTo(ChronoUnit.SECONDS)).sign();
    }

    String ticket(UUID session, Selection selection) { return ticket(session, selection, Instant.now()); }

    @Test
    void storageFailingBeforeTheFirstByteIsTheContractProblemNotAContainerErrorPage() throws Exception {
        var selection = ready("valid-160x96-silent.mp4");
        var session = UUID.randomUUID();
        String cookie = COOKIE + "=" + ticket(session, selection);
        for (var broken : List.of(Fault.FAIL_FIRST_READ, Fault.FAIL_AFTER_ONE_BUFFERED_BYTE)) {
            fault = broken;
            for (var name : List.of("master.m3u8", "segment-00000.ts")) {
                var reply = get("/v1/streams/" + session + "/" + name, "Cookie: " + cookie);
                problem(reply, 503, "MEDIA_UNAVAILABLE", broken + " " + name);
                assertThat(reply.header("retry-after")).as("a broken store is not a stream limit").isNull();
            }
        }
        fault = Fault.NONE;
        // Every failed request gave its share back: the session can still open all of its streams.
        for (int i = 0; i < 6; i++) {
            assertThat(get("/v1/streams/" + session + "/master.m3u8", "Cookie: " + cookie).status()).isEqualTo(200);
        }
    }

    @Test
    void anUnexpectedFailureBeforeTheFirstByteIsStillAProblemThePlayerRetries() throws Exception {
        var selection = ready("valid-160x96-silent.mp4");
        var session = UUID.randomUUID();
        fault = Fault.UNEXPECTED;
        String cookie = "Cookie: " + COOKIE + "=" + ticket(session, selection);
        var reply = get("/v1/streams/" + session + "/master.m3u8", cookie);
        problem(reply, 503, "MEDIA_UNAVAILABLE", "unexpected failure");
        assertThat(new String(reply.body(), StandardCharsets.UTF_8)).doesNotContain("SELECT", "IllegalState");
    }

    @Test
    void aTicketExpiringWhileStorageOpensIsAProblemAndTheExpiredTicketThenAuthenticatesNothing() throws Exception {
        var selection = ready("valid-160x96-silent.mp4");
        var session = UUID.randomUUID();
        // Issued 292 s ago: Core's five-minute ticket has eight seconds left, ample for a first request on a busy host.
        Instant issued = Instant.now().truncatedTo(ChronoUnit.SECONDS).minusSeconds(292);
        Instant expiry = issued.plusSeconds(300);
        String cookie = "Cookie: " + COOKIE + "=" + ticket(session, selection, issued);
        fault = Fault.SLOW_OPEN;
        openNotBefore = expiry.plusMillis(300);
        var reply = get("/v1/streams/" + session + "/segment-00000.ts", cookie);
        problem(reply, 503, "MEDIA_UNAVAILABLE", "deadline passed before the first byte");
        assertThat(reply.header("retry-after")).isNull();
        fault = Fault.NONE;
        problem(get("/v1/streams/" + session + "/segment-00000.ts", cookie), 401, "AUTHENTICATION_REQUIRED",
                "expired ticket");
    }

    @Test
    void aTicketExpiringBetweenPlaylistAndSegmentsStopsDeliveryUntilANewTicketArrives() throws Exception {
        var selection = ready("valid-160x96-silent.mp4");
        var session = UUID.randomUUID();
        Instant issued = Instant.now().truncatedTo(ChronoUnit.SECONDS).minusSeconds(292);
        String expiring = "Cookie: " + COOKIE + "=" + ticket(session, selection, issued);
        String base = "/v1/streams/" + session + "/";
        assertThat(get(base + "master.m3u8", expiring).status()).isEqualTo(200);
        var rendition = get(base + "rendition.m3u8", expiring);
        assertThat(rendition.status()).isEqualTo(200);
        var segments = references(rendition.body());
        sleepUntil(issued.plusSeconds(300).plusMillis(200));
        for (var name : List.of("master.m3u8", "rendition.m3u8", segments.getFirst(), segments.getLast())) {
            problem(get(base + name, expiring), 401, "AUTHENTICATION_REQUIRED", "after expiry " + name);
        }
        // Any later valid ticket for the path continues where it stopped. (Core itself renews only before expiry and
        // readmits as a new session afterwards; the smoke covers that.)
        String renewed = "Cookie: " + COOKIE + "=" + ticket(session, selection);
        for (var name : segments) {
            var reply = get(base + name, renewed);
            assertThat(reply.status()).as(name).isEqualTo(200);
            assertThat(reply.body()).isEqualTo(hlsBytes(selection.prefix() + "/" + name));
        }
    }

    @Test
    void noCachingOrConditionalHeaderLetsAnAuthorizedResponseBeReusedOrPartial() throws Exception {
        var selection = ready("valid-160x96-silent.mp4");
        var session = UUID.randomUUID();
        String cookie = "Cookie: " + COOKIE + "=" + ticket(session, selection);
        String path = "/v1/streams/" + session + "/segment-00000.ts";
        byte[] stored = hlsBytes(selection.prefix() + "/segment-00000.ts");
        var plain = get(path, cookie);
        assertThat(plain.status()).isEqualTo(200);
        assertThat(plain.header("cache-control")).isEqualTo("no-store");
        // Nothing a cache could validate, revalidate or serve in part.
        for (var absent : List.of("etag", "last-modified", "accept-ranges", "content-range", "age", "set-cookie")) {
            assertThat(plain.header(absent)).as(absent).isNull();
        }
        for (var conditional : List.of("If-None-Match: *", "If-None-Match: \"anything\"",
                "If-Modified-Since: Mon, 01 Jan 2090 00:00:00 GMT", "Range: bytes=0-0", "Range: bytes=100-",
                "If-Range: \"x\"", "Cache-Control: only-if-cached")) {
            var reply = get(path, cookie, conditional);
            assertThat(reply.status()).as(conditional).isEqualTo(200);
            assertThat(reply.body()).as(conditional).isEqualTo(stored);
            assertThat(reply.header("content-range")).as(conditional).isNull();
            // Without the ticket the same validators open nothing.
            problem(get(path, conditional), 401, "AUTHENTICATION_REQUIRED", "anonymous " + conditional);
        }
        // An authorized response never satisfies the next, unauthorized request for the same URL.
        problem(get(path), 401, "AUTHENTICATION_REQUIRED", "after an authorized response");
    }

    @Test
    void pathSpellingsTheContainerMightNormaliseNeverServeAnotherObjectOrAnyBytes() throws Exception {
        var selection = ready("valid-160x96-silent.mp4");
        var session = UUID.randomUUID();
        String cookie = "Cookie: " + COOKIE + "=" + ticket(session, selection);
        String s = session.toString();
        byte[] master = hlsBytes(selection.prefix() + "/master.m3u8");
        var observed = new LinkedHashMap<String, Integer>();
        for (var target : List.of(
                "/v1/streams;x=1/" + s + "/master.m3u8",
                "/v1/streams/" + s + ";x=1/master.m3u8",
                "/v1/streams/" + s + "/master.m3u8;x=1",
                "/v1/streams/" + s + "/master.m3u8;jsessionid=1",
                "/v1//streams/" + s + "/master.m3u8",
                "/v1/streams/" + s + "//master.m3u8",
                "/v1/streams/" + s + "/./master.m3u8",
                "/v1/streams/" + s + "/x/../master.m3u8",
                "/v1/streams/" + s + "/%2e/master.m3u8",
                "/v1/streams/" + s + "%2Fmaster.m3u8",
                "/v1/streams/" + s + "%5Cmaster.m3u8",
                "/v1/streams/" + s + "/%255c..%255cmaster.m3u8",
                "/v1/streams/" + s + "/master%2Em3u8",
                "/v1/streams/" + s + "/%6Daster.m3u8",
                "/v1/streams/" + s + "/master.m3u8%00",
                "/v1/streams/" + s + "/master.m3u8%20",
                "/v1/streams/" + s + "/master.m3u8/",
                "/v1/streams/" + s + "/master.m3u8.",
                "/V1/streams/" + s + "/master.m3u8",
                "/v1/Streams/" + s + "/master.m3u8",
                "/v1/streams/" + s.toUpperCase(Locale.ROOT) + "/master.m3u8",
                "/actuator/../v1/streams/" + s + "/master.m3u8",
                "/actuator/health/../../v1/streams/" + s + "/master.m3u8",
                "http://localhost/v1/streams/" + s + "/master.m3u8")) {
            var reply = get(target, cookie);
            observed.put(target.replace(s, "{session}").replace(s.toUpperCase(Locale.ROOT), "{SESSION}"),
                    reply.status());
            if (reply.status() == 200) {
                // Only a spelling the container decodes to this session's own master may answer, with its bytes.
                assertThat(reply.body()).as(target).isEqualTo(master);
                continue;
            }
            assertThat(reply.status()).as(target).isBetween(400, 404);
            assertThat(new String(reply.body(), StandardCharsets.ISO_8859_1)).as(target)
                    .doesNotContain("#EXTM3U", "hls/", "libra-hls", "Exception", "trace");
        }
        System.out.println("M30 Media spellings: " + observed);
    }

    @Test
    void aMissingObjectOfAReadyGenerationIsNotFoundAndNeverAnotherObject() throws Exception {
        var selection = ready("valid-160x96-silent.mp4");
        var session = UUID.randomUUID();
        String cookie = "Cookie: " + COOKIE + "=" + ticket(session, selection);
        s3.deleteObject(DeleteObjectRequest.builder().bucket("libra-hls")
                .key(selection.prefix() + "/segment-00001.ts").build());
        problem(get("/v1/streams/" + session + "/segment-00001.ts", cookie), 404, "NOT_FOUND", "missing segment");
        assertThat(get("/v1/streams/" + session + "/segment-00000.ts", cookie).body())
                .isEqualTo(hlsBytes(selection.prefix() + "/segment-00000.ts"));
    }

    @Test
    void playersThatHangUpMidBodyGiveTheirStreamsBack() throws Exception {
        var selection = ready("valid-720p-20s-aac.mp4");
        var session = UUID.randomUUID();
        String cookie = "Cookie: " + COOKIE + "=" + ticket(session, selection);
        String path = "/v1/streams/" + session + "/segment-00000.ts";
        fault = Fault.HOLD_AFTER_FIRST_CHUNK;
        held = new CountDownLatch(4);
        release = new CountDownLatch(1);
        var players = new ArrayList<Socket>();
        try {
            for (int i = 0; i < 4; i++) { players.add(open(path, cookie)); }
            assertThat(held.await(20, TimeUnit.SECONDS)).as("four streams in flight").isTrue();
            var refused = get(path, cookie);
            problem(refused, 503, "MEDIA_UNAVAILABLE", "fifth stream of the session");
            assertThat(refused.header("retry-after")).isEqualTo("1");
        } finally {
            for (var player : players) {
                player.setSoLinger(true, 0);
                player.close();
            }
            fault = Fault.NONE;
            release.countDown();
        }
        // The abandoned responses end (their writes fail or finish) and the session's whole share returns: four
        // streams held at once again.
        fault = Fault.HOLD_AFTER_FIRST_CHUNK;
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        boolean all = false;
        while (!all && System.nanoTime() < deadline) {
            held = new CountDownLatch(4);
            release = new CountDownLatch(1);
            var again = new ArrayList<Socket>();
            try {
                for (int i = 0; i < 4; i++) { again.add(open(path, cookie)); }
                all = held.await(3, TimeUnit.SECONDS);
            } finally {
                release.countDown();
                for (var player : again) { read(player.getInputStream()); player.close(); }
            }
        }
        fault = Fault.NONE;
        assertThat(all).as("four concurrent streams after the hang-ups").isTrue();
    }

    // ---- raw HTTP/1.1 over a socket ----

    record Reply(int status, Map<String, String> headers, byte[] body, boolean complete) {
        String header(String name) { return headers.get(name); }
    }

    private Reply get(String target, String... headers) throws IOException {
        try (var socket = open(target, headers)) { return read(socket.getInputStream()); }
    }

    private Socket open(String target, String... headers) throws IOException {
        var socket = new Socket("127.0.0.1", port);
        socket.setSoTimeout(30_000);
        var request = new StringBuilder("GET ").append(target).append(" HTTP/1.1\r\nHost: localhost\r\n")
                .append("Connection: close\r\n");
        for (var header : headers) { request.append(header).append("\r\n"); }
        socket.getOutputStream().write(request.append("\r\n").toString().getBytes(StandardCharsets.ISO_8859_1));
        socket.getOutputStream().flush();
        return socket;
    }

    /** Reads to the end of the connection; {@code complete} says whether the body was framed completely. */
    private static Reply read(InputStream input) throws IOException {
        var raw = new ByteArrayOutputStream();
        try { input.transferTo(raw); } catch (SocketException reset) { /* what arrived is what the player got */ }
        byte[] bytes = raw.toByteArray();
        int end = indexOf(bytes, "\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
        assertThat(end).as("a response head").isPositive();
        var lines = new String(bytes, 0, end, StandardCharsets.ISO_8859_1).split("\r\n");
        int status = Integer.parseInt(lines[0].split(" ")[1]);
        var headers = new LinkedHashMap<String, String>();
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            String name = lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT);
            assertThat(headers.put(name, lines[i].substring(colon + 1).trim())).as("repeated " + name).isNull();
        }
        byte[] payload = java.util.Arrays.copyOfRange(bytes, end + 4, bytes.length);
        if ("chunked".equalsIgnoreCase(headers.get("transfer-encoding"))) { return dechunk(status, headers, payload); }
        String length = headers.get("content-length");
        boolean complete = length != null && Long.parseLong(length) == payload.length;
        return new Reply(status, headers, payload, complete);
    }

    private static Reply dechunk(int status, Map<String, String> headers, byte[] payload) {
        var body = new ByteArrayOutputStream();
        int at = 0;
        while (true) {
            int line = indexOf(java.util.Arrays.copyOfRange(payload, at, payload.length), new byte[]{'\r', '\n'});
            if (line < 0) { return new Reply(status, headers, body.toByteArray(), false); }
            int size = Integer.parseInt(new String(payload, at, line, StandardCharsets.ISO_8859_1).trim(), 16);
            at += line + 2;
            if (size == 0) { return new Reply(status, headers, body.toByteArray(), true); }
            if (at + size > payload.length) { return new Reply(status, headers, body.toByteArray(), false); }
            body.write(payload, at, size);
            at += size + 2;
        }
    }

    private static int indexOf(byte[] bytes, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= bytes.length; i++) {
            for (int j = 0; j < needle.length; j++) { if (bytes[i + j] != needle[j]) { continue outer; } }
            return i;
        }
        return -1;
    }

    /** A complete, sanitized contract problem: framed body, problem type, no-store and the expected code. */
    private static void problem(Reply reply, int status, String code, String what) {
        String body = new String(reply.body(), StandardCharsets.UTF_8);
        assertThat(reply.status()).as(what + ": " + reply.headers() + " " + body).isEqualTo(status);
        assertThat(reply.complete()).as(what + " body framed by its own length").isTrue();
        assertThat(reply.header("content-type")).as(what).startsWith("application/problem+json");
        assertThat(reply.header("cache-control")).as(what).contains("no-store");
        assertThat(reply.header("set-cookie")).as(what).isNull();
        assertThat(body).as(what).contains("\"code\":\"" + code + "\"")
                .doesNotContain("hls/", "#EXTM3U", "libra-hls", "Exception", "STREAM_");
    }

    private static List<String> references(byte[] playlist) {
        return new String(playlist, StandardCharsets.UTF_8).lines()
                .filter(line -> !line.isBlank() && !line.startsWith("#")).toList();
    }

    private static void sleepUntil(Instant instant) {
        long millis = Duration.between(Instant.now(), instant).toMillis();
        if (millis > 0) {
            try { Thread.sleep(millis); } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
        }
    }

    private static void await(CountDownLatch latch) {
        try { latch.await(30, TimeUnit.SECONDS); } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
