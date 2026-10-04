package com.libra.streaming.media.storage;

import com.libra.streaming.media.processing.application.JobSources;
import com.libra.streaming.media.processing.application.SourceFreezer;
import com.libra.streaming.media.processing.application.SourceStorage;
import com.libra.streaming.media.processing.domain.JobLease;
import com.libra.streaming.media.processing.domain.ProcessingFailure;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import software.amazon.awssdk.services.s3.S3Client;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * Fault injection through a loopback S3 stand-in. SeaweedFS cannot be made to truncate, stall, drip or lie
 * about metadata, so these cases use the real SDK client and adapter against a scripted HTTP server.
 */
class SourceStorageFaultTest {
    private static final String KEY = "staging/" + UUID.randomUUID() + "/source.mp4";
    private static final byte[] CONTENT = bytes(4096, 1);
    @TempDir Path scratchRoot;
    private HttpServer server;
    private volatile Responder responder;
    private final AtomicInteger gets = new AtomicInteger();
    private final AtomicInteger puts = new AtomicInteger();
    private S3Client client;
    private S3MediaStorage storage;
    private S3SourceStorage sources;

    @FunctionalInterface
    interface Responder { void respond(HttpExchange exchange) throws Exception; }

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", exchange -> {
            try {
                if (exchange.getRequestMethod().equals("PUT")) { puts.incrementAndGet(); }
                else { gets.incrementAndGet(); }
                responder.respond(exchange);
            } catch (Exception ignored) {
                // The client aborting mid-body is an expected outcome for several cases.
            } finally {
                exchange.close();
            }
        });
        server.start();
        storage(() -> Long.MAX_VALUE);
    }

    @AfterEach
    void stop() throws Exception {
        server.stop(0);
        storage.close();
        client.close();
    }

    @Test
    void aBodyThatEndsBeforeItsDeclaredLengthIsATransientFaultNotASmallerObject() {
        responder = exchange -> {
            exchange.sendResponseHeaders(200, CONTENT.length);
            exchange.getResponseBody().write(CONTENT, 0, 2048);
            exchange.getResponseBody().flush();
            throw new IOException("connection dropped"); // closes the connection without the remaining bytes
        };
        assertUnavailable(() -> sources.stage(KEY, CONTENT.length, () -> false));
        assertThat(scratchParts()).isZero();
    }

    @Test
    void aShorterDeclaredLengthIsTrustedOnlyAsTheObjectsLengthAndThenRejectedAsASizeMismatch() throws Exception {
        responder = exchange -> {
            exchange.sendResponseHeaders(200, 3000);
            exchange.getResponseBody().write(CONTENT, 0, 3000);
        };
        try (var copy = sources.stage(KEY, CONTENT.length, () -> false).orElseThrow()) {
            assertThat(copy.bytes()).isEqualTo(3000);
        }
        assertRejected(freeze(), ProcessingFailure.SIZE_MISMATCH);
        assertThat(puts).hasValue(0);
    }

    @Test
    void forgedIntegrityHeadersAreIgnoredAndOnlyTheBytesDecide() throws Exception {
        byte[] other = bytes(CONTENT.length, 2);
        byte[] expected = MessageDigest.getInstance("SHA-256").digest(CONTENT);
        responder = exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "video/mp4");
            exchange.getResponseHeaders().add("ETag", "\"" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("MD5").digest(CONTENT)) + "\"");
            exchange.getResponseHeaders().add("x-amz-meta-sha256", HexFormat.of().formatHex(expected));
            exchange.getResponseHeaders().add("x-amz-checksum-sha256", Base64.getEncoder().encodeToString(expected));
            exchange.sendResponseHeaders(200, other.length);
            exchange.getResponseBody().write(other);
        };
        try (var copy = sources.stage(KEY, CONTENT.length, () -> false).orElseThrow()) {
            assertThat(copy.sha256()).isEqualTo(sha(other));
        }
        assertRejected(freeze(), ProcessingFailure.CHECKSUM_MISMATCH);
        assertThat(puts).hasValue(0);
    }

    @Test
    void aSlowlyDrippingBodyIsCutOffAtTheTransferDeadline() {
        responder = drip(Duration.ofMillis(100));
        long started = System.nanoTime();
        assertTimeoutPreemptively(Duration.ofSeconds(15), () -> {
            assertUnavailable(() -> sources.stage(KEY, CONTENT.length, () -> false));
            assertUnavailable(() -> sources.digest("sources/x/attempt-1/source.mp4", CONTENT.length, () -> false));
        });
        // Two transfers, each bounded by the 2s request timeout plus one read; dripping all bytes would take ~7 min.
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(8));
        assertThat(scratchParts()).isZero();
    }

    @Test
    void aStalledBodyFailsAtTheReadTimeout() {
        responder = exchange -> {
            exchange.sendResponseHeaders(200, CONTENT.length);
            exchange.getResponseBody().flush();
            Thread.sleep(20_000);
        };
        assertTimeoutPreemptively(Duration.ofSeconds(10),
                () -> assertUnavailable(() -> sources.stage(KEY, CONTENT.length, () -> false)));
        assertThat(scratchParts()).isZero();
    }

    @Test
    void cancellationOfADrippingTransferAbortsInsteadOfDrainingTheBody() {
        responder = drip(Duration.ofMillis(50));
        var polls = new AtomicInteger();
        assertTimeoutPreemptively(Duration.ofSeconds(5), () ->
                assertThatThrownBy(() -> sources.stage(KEY, CONTENT.length, () -> polls.incrementAndGet() > 3))
                        .isInstanceOf(InterruptedException.class));
        assertThat(scratchParts()).isZero();
    }

    @Test
    void anOversizedBodyIsAbortedAfterOneExtraByteEvenIfTheRestWouldTakeForever() {
        byte[] head = bytes(CONTENT.length + 1, 3);
        responder = exchange -> {
            exchange.sendResponseHeaders(200, 100_000);
            exchange.getResponseBody().write(head);
            exchange.getResponseBody().flush();
            for (int i = 0; i < 100_000; i++) { exchange.getResponseBody().write(0); exchange.getResponseBody().flush(); Thread.sleep(50); }
        };
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            try (var copy = sources.stage(KEY, CONTENT.length, () -> false).orElseThrow()) {
                assertThat(copy.bytes()).isEqualTo(CONTENT.length + 1);
            }
        });
    }

    @Test
    void exhaustedScratchSpaceFailsBeforeAnyRequestAndIsTransient() throws Exception {
        storage.close();
        storage(() -> 1024);
        responder = exchange -> { exchange.sendResponseHeaders(200, CONTENT.length); exchange.getResponseBody().write(CONTENT); };
        assertUnavailable(() -> sources.stage(KEY, CONTENT.length, () -> false));
        assertThat(gets).hasValue(0);
        var result = (SourceFreezer.Result.Rejected) freeze();
        assertThat(result.outcome().permanent()).isFalse();
        assertThat(result.outcome().failure()).isEqualTo(ProcessingFailure.PROCESSING_FAILED);
        assertThat(scratchParts()).isZero();
    }

    private SourceFreezer.Result freeze() throws InterruptedException {
        var lease = new JobLease(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, 1, UUID.randomUUID(),
                Instant.now().plusSeconds(30));
        JobSources target = new JobSources() {
            @Override public Optional<Target> target(JobLease ignored) {
                return Optional.of(new Target(KEY, CONTENT.length, sha(CONTENT), null));
            }
            @Override public boolean select(JobLease ignored, String key) { throw new AssertionError("selected " + key); }
        };
        return new SourceFreezer(target, sources).freeze(lease, () -> false);
    }

    private static void assertRejected(SourceFreezer.Result result, ProcessingFailure failure) {
        assertThat(result).isInstanceOf(SourceFreezer.Result.Rejected.class);
        var outcome = ((SourceFreezer.Result.Rejected) result).outcome();
        assertThat(outcome.failure()).isEqualTo(failure);
        assertThat(outcome.permanent()).isTrue();
    }

    private static void assertUnavailable(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(SourceStorage.Unavailable.class).hasNoCause();
    }

    private static Responder drip(Duration interval) {
        return exchange -> {
            exchange.sendResponseHeaders(200, CONTENT.length);
            for (byte value : CONTENT) {
                exchange.getResponseBody().write(value);
                exchange.getResponseBody().flush();
                Thread.sleep(interval.toMillis());
            }
        };
    }

    private void storage(LongSupplier usable) throws Exception {
        String endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
        var properties = new MediaStorageProperties(endpoint, endpoint, "http://localhost:8080", "us-east-1",
                "fixture", "fixture-secret-0123456789", "libra-source", "libra-hls", "staging/", "sources/", "hls/",
                Duration.ofSeconds(1), Duration.ofSeconds(2), 1048576, scratchRoot, true, false, false);
        if (client != null) { client.close(); }
        client = new MediaStorageConfiguration().mediaS3Client(properties);
        storage = new S3MediaStorage(client, properties, new ScratchSpace(scratchRoot, usable));
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("storage", storage);
        beans.registerSingleton("properties", properties);
        sources = new S3SourceStorage(beans.getBeanProvider(S3MediaStorage.class),
                beans.getBeanProvider(MediaStorageProperties.class));
    }

    private long scratchParts() {
        try (Stream<Path> files = Files.walk(scratchRoot)) {
            return files.filter(path -> path.getFileName().toString().endsWith(".part")).count();
        } catch (IOException exception) { throw new AssertionError(exception); }
    }

    private static byte[] bytes(int length, long seed) {
        byte[] value = new byte[length];
        new Random(seed).nextBytes(value);
        return value;
    }

    private static String sha(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (Exception exception) { throw new AssertionError(exception); }
    }
}
