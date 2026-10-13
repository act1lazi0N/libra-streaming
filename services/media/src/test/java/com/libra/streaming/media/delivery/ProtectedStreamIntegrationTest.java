package com.libra.streaming.media.delivery;

import com.libra.streaming.media.processing.application.OutputFinalizer;
import com.libra.streaming.media.processing.infrastructure.FinalizationFixture;
import com.libra.streaming.media.processing.infrastructure.MediaFixtures;
import com.libra.streaming.media.security.CoreTicketFixture;
import jakarta.servlet.http.Cookie;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Protected delivery of a generation the production pipeline made READY: real PostgreSQL holds the READY record, real
 * SeaweedFS holds the private HLS objects, the real ffmpeg encoded them and decodes what was served. Requests pass
 * through the whole Spring Security chain with tickets signed the way Core signs them; only the ingress (Nginx) and
 * Core's own signer are outside this suite.
 */
@DirtiesContext
class ProtectedStreamIntegrationTest extends FinalizationFixture {
    static final CoreTicketFixture CORE = new CoreTicketFixture();
    static final CoreTicketFixture FOREIGN = new CoreTicketFixture();
    static final String COOKIE = "__Secure-LIBRA_PLAYBACK";
    static final List<String> ROUTES = List.of("master.m3u8", "rendition.m3u8", "segment-00000.ts");

    @DynamicPropertySource
    static void playback(DynamicPropertyRegistry registry) {
        registry.add("libra.media.playback.enabled", () -> true);
        registry.add("libra.media.playback.public-key", CORE::publicKey);
        registry.add("libra.media.playback.key-id", () -> CoreTicketFixture.KID);
        registry.add("libra.media.playback.secure-cookie", () -> true);
    }

    @Autowired WebApplicationContext context;
    MockMvc mvc;
    final UUID session = UUID.randomUUID();

    @BeforeEach void mvc() { mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build(); }

    /** The exact selection Media recorded for the current upload, as Core would put it in a ticket. */
    record Selection(UUID content, UUID binding, UUID asset, long version, String prefix) {}

    Selection ready(String fixture) throws Exception {
        var lease = stagedClaim(fixture);
        assertThat(finalizer.complete(lease, () -> false)).isInstanceOf(OutputFinalizer.Result.Ready.class);
        return selection();
    }

    Selection selection() {
        Map<String, Object> row = asset();
        return new Selection((UUID) row.get("content_id"), (UUID) row.get("binding_id"), (UUID) row.get("asset_id"),
                (Long) row.get("asset_version"), (String) row.get("output_prefix"));
    }

    CoreTicketFixture.Ticket ticket(UUID forSession, Selection selection) {
        return CORE.ticket(forSession, selection.content(), selection.binding(), selection.asset(), selection.version(),
                Instant.now().truncatedTo(ChronoUnit.SECONDS));
    }

    MockHttpServletRequestBuilder stream(UUID path, String name) {
        return get("/v1/streams/{session}/{name}", path, name);
    }

    @Test
    void aTicketForTheSessionDeliversTheWholeReadyGenerationExactlyAsStoredAndItPlays() throws Exception {
        var selection = ready("valid-720p-20s-aac.mp4");
        var cookie = new Cookie(COOKIE, ticket(session, selection).sign());
        var served = Files.createTempDirectory(Path.of("target"), "m27-served-");

        byte[] master = fetch(cookie, "master.m3u8", "application/vnd.apple.mpegurl", selection);
        assertThat(references(master)).containsExactly("rendition.m3u8");
        byte[] rendition = fetch(cookie, "rendition.m3u8", "application/vnd.apple.mpegurl", selection);
        var segments = references(rendition);
        assertThat(segments).hasSizeGreaterThanOrEqualTo(4).allMatch(name -> name.matches("segment-\\d{5}\\.ts"));
        Files.write(served.resolve("master.m3u8"), master);
        Files.write(served.resolve("rendition.m3u8"), rendition);
        long segmentBytes = 0;
        for (var name : segments) {
            byte[] segment = fetch(cookie, name, "video/mp2t", selection);
            segmentBytes += segment.length;
            Files.write(served.resolve(name), segment);
        }
        // Everything the generation holds was reachable, and nothing else exists under it.
        var stored = new ArrayList<String>(List.of(selection.prefix() + "/master.m3u8",
                selection.prefix() + "/rendition.m3u8"));
        segments.forEach(name -> stored.add(selection.prefix() + "/" + name));
        assertThat(hlsKeys(selection.prefix() + "/")).containsExactlyInAnyOrderElementsOf(stored);
        assertThat(segmentBytes).isPositive();

        // A real HLS client resolves the served playlists' relative names and decodes every served byte.
        var decode = new ProcessBuilder(MediaFixtures.ffmpeg().toString(), "-nostdin", "-v", "error",
                "-i", served.resolve("master.m3u8").toString(), "-map", "0", "-f", "null", "-")
                .redirectErrorStream(true).start();
        assertThat(decode.waitFor(120, TimeUnit.SECONDS)).isTrue();
        String errors = new String(decode.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(decode.exitValue()).as(errors).isZero();
        assertThat(errors).isBlank();
    }

    @Test
    void everyRouteRefusesAMissingInvalidMisplacedOrMisdirectedTicket() throws Exception {
        var selection = ready("valid-160x96-silent.mp4");
        String valid = ticket(session, selection).sign();
        String otherSession = ticket(UUID.randomUUID(), selection).sign();
        String expired = CORE.ticket(session, selection.content(), selection.binding(), selection.asset(),
                selection.version(), Instant.now().minusSeconds(301)).sign();
        String foreign = ticket(session, selection).signedBy(FOREIGN.keyPair()).sign();
        String serviceToken = ticket(session, selection).claim("iss", "libra-core-services")
                .claim("aud", List.of("libra-media-internal")).claim("purpose", "service-access").sign();
        for (var name : ROUTES) {
            mvc.perform(stream(session, name).cookie(new Cookie(COOKIE, valid))).andExpect(status().isOk());

            unauthorized(stream(session, name));
            unauthorized(stream(session, name).cookie(new Cookie("LIBRA_PLAYBACK", valid)));
            unauthorized(stream(session, name).header("Authorization", "Bearer " + valid));
            unauthorized(stream(session, name).header("Authorization", "Bearer " + valid)
                    .cookie(new Cookie(COOKIE, valid)));
            unauthorized(stream(session, name).cookie(new Cookie(COOKIE, valid), new Cookie(COOKIE, valid)));
            unauthorized(stream(session, name).queryParam("access_token", valid));
            unauthorized(stream(session, name).cookie(new Cookie(COOKIE, expired)));
            unauthorized(stream(session, name).cookie(new Cookie(COOKIE, foreign)));
            unauthorized(stream(session, name).cookie(new Cookie(COOKIE, serviceToken)));
            unauthorized(stream(session, name).cookie(new Cookie(COOKIE, valid + "x")));

            // A valid ticket opens its own session's path only.
            forbidden(stream(session, name).cookie(new Cookie(COOKIE, otherSession)));
            forbidden(stream(UUID.randomUUID(), name).cookie(new Cookie(COOKIE, valid)));
            for (var method : List.of(HttpMethod.POST, HttpMethod.PUT, HttpMethod.DELETE, HttpMethod.HEAD)) {
                forbidden(request(method, "/v1/streams/{session}/{name}", session, name)
                        .cookie(new Cookie(COOKIE, valid)));
            }
        }
    }

    @Test
    void aTicketOpensOnlyTheGenerationCoreSelectedAndOnlyOnceItIsReady() throws Exception {
        var a = ready("valid-160x96-silent.mp4");
        var aUpload = upload;
        stagedClaim("valid-160x96-silent.mp4"); // a second asset, claimed and PROCESSING, with no output selected
        var b = selection();
        assertThat(asset().get("state")).isEqualTo("PROCESSING");
        upload = aUpload;

        mvc.perform(stream(session, "master.m3u8").cookie(cookie(a))).andExpect(status().isOk());
        notFound(stream(session, "master.m3u8").cookie(cookie(b)));
        notFound(stream(session, "master.m3u8").cookie(cookie(new Selection(a.content(), a.binding(), a.asset(),
                a.version() + 1, a.prefix()))));
        notFound(stream(session, "master.m3u8").cookie(cookie(new Selection(a.content(), a.binding(),
                UUID.randomUUID(), a.version(), a.prefix()))));
        // Core's selection must be the one Media recorded: another binding or content for the same asset is refused.
        forbidden(stream(session, "master.m3u8").cookie(cookie(new Selection(a.content(), b.binding(), a.asset(),
                a.version(), a.prefix()))));
        forbidden(stream(session, "master.m3u8").cookie(cookie(new Selection(b.content(), a.binding(), a.asset(),
                a.version(), a.prefix()))));
        // B's ticket can never reach A's objects: the key comes from B's own record, which has no output.
        notFound(stream(session, "segment-00000.ts").cookie(cookie(b)));
    }

    @Test
    void namesOutsideTheGenerationReachNothing() throws Exception {
        var selection = ready("valid-160x96-silent.mp4");
        var cookie = cookie(selection);
        for (var name : List.of("source.mp4", "segment-00300.ts", "segment-00299.ts", "master.M3U8",
                "rendition.m3u8.bak", "sources", "staging")) {
            notFound(stream(session, name).cookie(cookie));
        }
        for (var path : List.of("/v1/streams/" + session + "/..%2Fattempt-1%2Fmaster.m3u8",
                "/v1/streams/" + session + "/%2e%2e/master.m3u8",
                "/v1/streams/" + session + "/../" + session + "/master.m3u8",
                "/v1/streams/" + session + "/x/master.m3u8",
                "/v1/streams/" + session + "/",
                "/v1/streams/" + session)) {
            var result = mvc.perform(get(URI.create(path)).cookie(cookie)).andReturn();
            assertThat(result.getResponse().getStatus()).as(path).isBetween(400, 404);
            assertThat(result.getResponse().getContentAsString()).as(path).doesNotContain("hls/", "#EXTM3U");
        }
    }

    private Cookie cookie(Selection selection) { return new Cookie(COOKIE, ticket(session, selection).sign()); }

    private byte[] fetch(Cookie cookie, String name, String type, Selection selection) throws Exception {
        MvcResult result = mvc.perform(stream(session, name).cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(content().contentType(type))
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andReturn();
        byte[] body = result.getResponse().getContentAsByteArray();
        assertThat(result.getResponse().getContentLengthLong()).isEqualTo(body.length);
        assertThat(body).isEqualTo(hlsBytes(selection.prefix() + "/" + name));
        return body;
    }

    private static List<String> references(byte[] playlist) {
        return new String(playlist, StandardCharsets.UTF_8).lines()
                .filter(line -> !line.isBlank() && !line.startsWith("#")).toList();
    }

    private void unauthorized(RequestBuilder request) throws Exception {
        problem(request, 401, "AUTHENTICATION_REQUIRED");
    }

    private void forbidden(RequestBuilder request) throws Exception { problem(request, 403, "ACCESS_DENIED"); }

    private void notFound(RequestBuilder request) throws Exception { problem(request, 404, "NOT_FOUND"); }

    private void problem(RequestBuilder request, int status, String code) throws Exception {
        var result = mvc.perform(request).andExpect(status().is(status))
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(header().doesNotExist("WWW-Authenticate"))
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andReturn();
        if (!"HEAD".equals(result.getRequest().getMethod())) {
            assertThat(result.getResponse().getContentAsString()).contains("\"code\":\"" + code + "\"")
                    // Only the requested path is echoed (as the problem instance), never a key, bucket or playlist.
                    .doesNotContain("hls/", "#EXTM3U", "libra-hls", "/attempt-");
        }
    }
}
