package com.libra.streaming.media.delivery.api;

import com.libra.streaming.media.delivery.application.StreamDelivery;
import com.libra.streaming.media.delivery.application.StreamObjects;
import com.libra.streaming.media.delivery.domain.PlaybackGrant;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Protected HLS: {@code /v1/streams/{sessionId}/master.m3u8}, {@code rendition.m3u8} and {@code segment-NNNNN.ts}.
 * Nginx forwards {@code /api/media/v1/streams/...}, the path Core scopes the ticket cookie to. The security chain has
 * already verified the ticket and its session; this resolves the asset generation from the ticket's own claims.
 */
@RestController
public class StreamController {
    private final StreamDelivery delivery;

    public StreamController(StreamDelivery delivery) { this.delivery = delivery; }

    @GetMapping("/v1/streams/{sessionId}/{name}")
    void stream(@PathVariable String sessionId, @PathVariable String name, @AuthenticationPrincipal Jwt ticket,
            HttpServletResponse response) throws IOException {
        var grant = grant(ticket);
        // The chain matched the session already; repeated here so this route can never serve another session.
        if (!grant.sessionId().toString().equals(sessionId)) { throw new StreamDelivery.Denied(); }
        try (var stream = delivery.open(grant, name)) {
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType(stream.contentType());
            response.setContentLengthLong(stream.body().length());
            // Personal, short-lived authorization: no shared or browser cache may keep or replay it.
            response.setHeader("Cache-Control", "no-store");
            response.setHeader("X-Content-Type-Options", "nosniff");
            var sink = response.getOutputStream();
            try {
                stream.body().transferTo(sink);
            } catch (StreamObjects.Unavailable broken) {
                // Headers are gone: only cutting the connection tells the player this body is incomplete.
                throw new Interrupted();
            } catch (IOException clientGone) {
                // The player stopped reading; there is nobody left to answer.
            }
        }
    }

    private static PlaybackGrant grant(Jwt ticket) {
        return new PlaybackGrant(UUID.fromString(ticket.getSubject()),
                UUID.fromString(ticket.getClaimAsString("contentId")),
                UUID.fromString(ticket.getClaimAsString("bindingId")),
                UUID.fromString(ticket.getClaimAsString("assetId")),
                ticket.<Long>getClaim("assetVersion"));
    }

    /** Storage failed after the response was committed; deliberately unhandled so the container aborts the response. */
    static final class Interrupted extends RuntimeException {
        Interrupted() { super("STREAM_INTERRUPTED", null, false, false); }
    }
}
