package com.libra.streaming.media.delivery.api;

import com.libra.streaming.media.delivery.application.DeliverableAssets;
import com.libra.streaming.media.delivery.application.StreamDelivery;
import com.libra.streaming.media.delivery.application.StreamObjects;
import com.libra.streaming.media.delivery.domain.PlaybackGrant;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * The route's response handling with an already authenticated ticket and in-memory ports: headers, problem bodies, and
 * what happens once bytes have been sent. Ticket verification itself is covered by the security tests.
 */
class StreamControllerTest {
    final UUID session = UUID.randomUUID();
    final UUID content = UUID.randomUUID();
    final UUID binding = UUID.randomUUID();
    final UUID asset = UUID.randomUUID();
    final String prefix = "hls/" + UUID.randomUUID() + "/attempt-1";
    DeliverableAssets assets = (id, version) -> Optional.of(
            new DeliverableAssets.Deliverable(content, binding, "READY", prefix, prefix + "/master.m3u8"));
    Function<String, Optional<StreamObjects.Opened>> objects = key -> Optional.of(body(new byte[]{0x47, 1, 2}, null));
    Clock clock = Clock.systemUTC();
    StreamDelivery delivery;

    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    MockMvc mvc() {
        var jwt = Jwt.withTokenValue("ticket").header("alg", "RS256").subject(session.toString())
                .claim("contentId", content.toString()).claim("bindingId", binding.toString())
                .claim("assetId", asset.toString()).claim("assetVersion", 1L)
                .issuedAt(clock.instant()).expiresAt(clock.instant().plusSeconds(300)).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        delivery = new StreamDelivery((id, version) -> assets.find(id, version), (key, max) -> objects.apply(key),
                new StreamDelivery.Limits(3, 2, Duration.ofSeconds(60)), clock);
        return MockMvcBuilders.standaloneSetup(new StreamController(delivery))
                .setControllerAdvice(new StreamProblems())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();
    }

    @Test
    void anObjectIsStreamedWithItsLengthTypeAndNoCaching() throws Exception {
        mvc().perform(get("/v1/streams/{session}/segment-00000.ts", session))
                .andExpect(status().isOk())
                .andExpect(content().contentType("video/mp2t"))
                .andExpect(header().longValue("Content-Length", 3))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(content().bytes(new byte[]{0x47, 1, 2}));
    }

    @Test
    void failuresBeforeTheFirstByteAreProblemsWithoutDetail() throws Exception {
        expectProblem(get("/v1/streams/{session}/source.mp4", session), 404, "NOT_FOUND");
        objects = key -> Optional.empty();
        expectProblem(get("/v1/streams/{session}/master.m3u8", session), 404, "NOT_FOUND");
        objects = key -> { throw new StreamObjects.Unavailable(); };
        expectProblem(get("/v1/streams/{session}/master.m3u8", session), 503, "MEDIA_UNAVAILABLE");
        assets = (id, version) -> { throw new QueryTimeoutException("SELECT secret FROM media_assets"); };
        expectProblem(get("/v1/streams/{session}/master.m3u8", session), 503, "MEDIA_UNAVAILABLE");
        assets = (id, version) -> Optional.of(
                new DeliverableAssets.Deliverable(content, UUID.randomUUID(), "READY", prefix, prefix + "/master.m3u8"));
        expectProblem(get("/v1/streams/{session}/master.m3u8", session), 403, "ACCESS_DENIED");
        // The route repeats the chain's session check: a principal never opens another session's path.
        expectProblem(get("/v1/streams/{session}/master.m3u8", UUID.randomUUID()), 403, "ACCESS_DENIED");
    }

    @Test
    void storageFailingMidBodyAbortsTheResponseInsteadOfEndingItShort() {
        objects = key -> Optional.of(body(new byte[]{0x47}, new StreamObjects.Unavailable()));
        assertThatThrownBy(() -> mvc().perform(get("/v1/streams/{session}/segment-00001.ts", session)))
                .isInstanceOf(ServletException.class).cause().isInstanceOf(StreamController.Interrupted.class)
                .hasMessage("STREAM_INTERRUPTED");
    }

    @Test
    void aPlayerThatStopsReadingEndsTheRequestQuietlyAndTheObjectIsReleased() throws Exception {
        var released = new boolean[1];
        objects = key -> Optional.of(new StreamObjects.Opened() {
            @Override public long length() { return 3; }
            @Override public void transferTo(OutputStream sink) throws IOException { throw new IOException("reset"); }
            @Override public void close() { released[0] = true; }
        });
        mvc().perform(get("/v1/streams/{session}/segment-00002.ts", session)).andExpect(status().isOk());
        assertThat(released[0]).isTrue();
    }

    @Test
    void aSessionOrInstanceAtItsStreamLimitIsToldToRetryWithoutDetail() throws Exception {
        var mvc = mvc();
        var grant = grant(session, content, binding, asset, 1);
        try (var a = delivery.open(grant, "master.m3u8"); var b = delivery.open(grant, "segment-00000.ts")) {
            var result = expectProblem(mvc, get("/v1/streams/{session}/segment-00001.ts", session), 503,
                    "MEDIA_UNAVAILABLE");
            assertThat(result.getResponse().getHeader("Retry-After")).isEqualTo("1");
            try (var c = delivery.open(grant(UUID.randomUUID(), content, binding, asset, 1),
                    "master.m3u8")) {
                // The instance is full too; the refusal is the same retryable answer.
                assertThat(expectProblem(mvc, get("/v1/streams/{session}/segment-00001.ts", session), 503,
                        "MEDIA_UNAVAILABLE").getResponse().getHeader("Retry-After")).isEqualTo("1");
            }
        }
        try (var a = delivery.open(grant(UUID.randomUUID(), content, binding, asset, 1), "master.m3u8");
                var b = delivery.open(grant(UUID.randomUUID(), content, binding, asset, 1), "master.m3u8");
                var c = delivery.open(grant(UUID.randomUUID(), content, binding, asset, 1),
                        "master.m3u8")) {
            var result = expectProblem(mvc, get("/v1/streams/{session}/master.m3u8", session), 503,
                    "MEDIA_UNAVAILABLE");
            assertThat(result.getResponse().getHeader("Retry-After")).isEqualTo("1");
        }
        // Every refused and every served request gave its share back.
        for (int i = 0; i < 5; i++) {
            mvc.perform(get("/v1/streams/{session}/segment-00000.ts", session)).andExpect(status().isOk());
        }
        sessionSharesAreAllFree();
        // A failure before the first byte is a 503 without Retry-After: retrying cannot help a broken store.
        objects = key -> { throw new StreamObjects.Unavailable(); };
        assertThat(expectProblem(mvc, get("/v1/streams/{session}/master.m3u8", session), 503, "MEDIA_UNAVAILABLE")
                .getResponse().getHeader("Retry-After")).isNull();
        objects = key -> Optional.of(body(new byte[]{0x47, 1, 2}, null));
        sessionSharesAreAllFree();
    }

    @Test
    void aTransferPastItsDeadlineIsCutAndItsShareReleased() throws Exception {
        var moving = new java.util.concurrent.atomic.AtomicReference<>(Instant.parse("2026-10-13T09:00:00Z"));
        clock = new Clock() {
            @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return moving.get(); }
        };
        var released = new boolean[1];
        objects = key -> Optional.of(new StreamObjects.Opened() {
            @Override public long length() { return 3; }
            @Override public void transferTo(OutputStream sink) throws IOException {
                sink.write(new byte[]{0x47}, 0, 1);
                moving.set(moving.get().plusSeconds(60));
                sink.write(new byte[]{1, 2}, 0, 2);
            }
            @Override public void close() { released[0] = true; }
        });
        var mvc = mvc();
        assertThatThrownBy(() -> mvc.perform(get("/v1/streams/{session}/segment-00003.ts", session)))
                .isInstanceOf(ServletException.class).cause().isInstanceOf(StreamController.Interrupted.class);
        assertThat(released[0]).isTrue();
        sessionSharesAreAllFree();
    }

    @Test
    void storageFailureAndAGonePlayerAlsoGiveTheSessionsShareBack() throws Exception {
        var mvc = mvc();
        objects = key -> Optional.of(body(new byte[]{0x47}, new StreamObjects.Unavailable()));
        assertThatThrownBy(() -> mvc.perform(get("/v1/streams/{session}/segment-00001.ts", session)))
                .cause().isInstanceOf(StreamController.Interrupted.class);
        objects = key -> Optional.of(new StreamObjects.Opened() {
            @Override public long length() { return 3; }
            @Override public void transferTo(OutputStream sink) throws IOException { throw new IOException("reset"); }
            @Override public void close() {}
        });
        mvc.perform(get("/v1/streams/{session}/segment-00002.ts", session)).andExpect(status().isOk());
        sessionSharesAreAllFree();
    }

    PlaybackGrant grant(UUID session, UUID content, UUID binding, UUID asset, long version) {
        return new PlaybackGrant(session, content, binding, asset, version, clock.instant().plusSeconds(300));
    }

    /** Both of the session's streams can be held at once: every earlier request gave its share back. */
    private void sessionSharesAreAllFree() {
        try (var a = delivery.open(grant(session, content, binding, asset, 1), "master.m3u8");
                var b = delivery.open(grant(session, content, binding, asset, 1), "master.m3u8")) {
            assertThat(a.length() + b.length()).isPositive();
        }
    }

    private void expectProblem(org.springframework.test.web.servlet.RequestBuilder request, int status, String code)
            throws Exception {
        expectProblem(mvc(), request, status, code);
    }

    private org.springframework.test.web.servlet.MvcResult expectProblem(MockMvc mvc,
            org.springframework.test.web.servlet.RequestBuilder request, int status, String code) throws Exception {
        return mvc.perform(request).andExpect(status().is(status))
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("hls/"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("SELECT"))))
                .andReturn();
    }

    private static StreamObjects.Opened body(byte[] bytes, RuntimeException failAfter) {
        return new StreamObjects.Opened() {
            @Override public long length() { return failAfter == null ? bytes.length : bytes.length + 10; }
            @Override public void transferTo(OutputStream sink) throws IOException {
                sink.write(bytes);
                if (failAfter != null) { throw failAfter; }
            }
            @Override public void close() {}
        };
    }
}
