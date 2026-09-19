package com.libra.streaming.core.playback;

import com.libra.streaming.core.api.CorrelationIdFilter;
import com.libra.streaming.core.api.DomainException;
import com.libra.streaming.core.identity.IdentityPrincipal;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import static com.libra.streaming.core.playback.PlaybackModels.*;

@RestController
public class PlaybackController {
    private final PlaybackService playback;
    private final PlaybackTickets tickets;

    public PlaybackController(PlaybackService playback, PlaybackTickets tickets) {
        this.playback = playback; this.tickets = tickets;
    }

    @PostMapping("/v1/playback/sessions")
    @ResponseStatus(HttpStatus.CREATED)
    SessionView admit(@AuthenticationPrincipal IdentityPrincipal actor, @Valid @RequestBody Admission request,
            HttpServletResponse response) {
        return issue(playback.admit(actor, request), response);
    }

    @PostMapping("/v1/playback/sessions/{id}/renew")
    SessionView renew(@AuthenticationPrincipal IdentityPrincipal actor, @PathVariable UUID id, HttpServletResponse response) {
        return issue(playback.renew(actor, id), response);
    }

    @PostMapping("/v1/playback/sessions/{id}/progress")
    ProgressView progress(@AuthenticationPrincipal IdentityPrincipal actor, @PathVariable UUID id,
            @Valid @RequestBody Progress request, @RequestAttribute(CorrelationIdFilter.ATTRIBUTE) UUID correlationId,
            HttpServletResponse response) {
        var result = playback.progress(actor, id, request, correlationId);
        if (result.state() == State.ENDED) { response.addHeader(HttpHeaders.SET_COOKIE, tickets.clearCookie(id)); }
        return result;
    }

    @PostMapping("/v1/playback/sessions/{id}/end")
    ProgressView end(@AuthenticationPrincipal IdentityPrincipal actor, @PathVariable UUID id,
            @Valid @RequestBody Progress request, @RequestAttribute(CorrelationIdFilter.ATTRIBUTE) UUID correlationId,
            HttpServletResponse response) {
        if (request.state() != State.ENDED) { throw DomainException.invalid(); }
        return progress(actor, id, request, correlationId, response);
    }

    @GetMapping("/v1/playback/jwks")
    Map<String, Object> publicKeys() { return tickets.publicKeys(); }

    private SessionView issue(Issued issued, HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.addHeader(HttpHeaders.SET_COOKIE, tickets.cookie(issued.view().sessionId(), issued.ticket(), issued.view().expiresAt()));
        return issued.view();
    }
}
