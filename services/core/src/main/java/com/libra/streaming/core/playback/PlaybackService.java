package com.libra.streaming.core.playback;

import com.libra.streaming.core.api.DomainException;
import com.libra.streaming.core.entitlement.application.EntitlementOperations;
import com.libra.streaming.core.entitlement.application.EntitlementOperations.EligibleContent;
import com.libra.streaming.core.history.application.HistoryOperations;
import com.libra.streaming.core.identity.IdentityAccess;
import com.libra.streaming.core.identity.IdentityPrincipal;
import com.libra.streaming.core.events.domain.CoreEventTopic;
import com.libra.streaming.core.events.application.EventPort;
import jakarta.validation.Validator;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.libra.streaming.core.playback.PlaybackModels.*;

@Service
public class PlaybackService {
    private final IdentityAccess access;
    private final EntitlementOperations entitlements;
    private final PlaybackStore store;
    private final PlaybackTickets tickets;
    private final HistoryOperations history;
    private final EventPort events;
    private final Clock clock;
    private final Validator validator;

    public PlaybackService(IdentityAccess access, EntitlementOperations entitlements, PlaybackStore store,
            PlaybackTickets tickets, HistoryOperations history, EventPort events, Clock clock, Validator validator) {
        this.access = access; this.entitlements = entitlements; this.store = store; this.tickets = tickets;
        this.history = history; this.events = events; this.clock = clock; this.validator = validator;
    }

    @Transactional
    public Issued admit(IdentityPrincipal actor, Admission request) {
        access.lockCurrent(actor, false);
        validate(request);
        store.lockCatalog(request.contentId());
        var eligible = entitlements.requireEligible(actor, request.profileId(), request.contentId());
        var expiry = tickets.expiry(eligible);
        UUID id = UUID.randomUUID();
        store.insert(id, actor, eligible, clock.instant(), expiry);
        long resume = history.opened(request.profileId(), request.contentId(), id, eligible.durationSeconds() * 1000L);
        store.position(id, resume);
        return new Issued(new SessionView(id, request.profileId(), request.contentId(), eligible.durationSeconds() * 1000L,
                resume, expiry, PlaybackTickets.streamPath(id) + "master.m3u8", 15), tickets.sign(id, eligible, expiry));
    }

    @Transactional
    public Issued renew(IdentityPrincipal actor, UUID id) {
        access.lockCurrent(actor, false);
        var session = store.owned(actor, id);
        requireLive(session);
        var eligible = eligible(actor, session);
        requireLive(session); // Catalog locks may have waited across the session expiry boundary.
        var expiry = tickets.expiry(eligible);
        store.renew(id, expiry);
        return new Issued(new SessionView(id, session.profileId(), session.contentId(), session.durationMs(),
                session.positionMs(), expiry, PlaybackTickets.streamPath(id) + "master.m3u8", 15), tickets.sign(id, eligible, expiry));
    }

    @Transactional
    public ProgressView progress(IdentityPrincipal actor, UUID id, Progress request, UUID correlationId) {
        access.lockCurrent(actor, false);
        validate(request);
        var session = store.owned(actor, id);
        // A duplicate terminal request is safe too, but never writes history or emits another event.
        if (request.sequence() <= session.sequence()) { return view(session, false); }
        requireLive(session);
        eligible(actor, session);
        requireLive(session);
        if (request.positionMs() > session.durationMs()) { throw DomainException.invalid(); }
        var now = clock.instant();
        long elapsed = Duration.between(session.lastProgressAt(), now).toMillis();
        // Gaps beyond two heartbeats earn no time. Seeking never manufactures watched duration.
        long accepted = session.state() == State.PLAYING && request.state() != State.SEEKING
                && elapsed >= 0 && elapsed <= 30000 ? Math.min(request.playedMs(), elapsed) : 0;
        long watched = Math.addExact(session.watchedMs(), accepted);
        store.progress(id, request, watched, now);
        history.accepted(id, request.positionMs(), session.durationMs(), now);
        events.append(CoreEventTopic.PLAYBACK, "PlaybackProgressAccepted", id, request.sequence(), correlationId,
                new ViewingEvent(actor.accountId(), session.profileId(), id, session.contentId(), session.assetId(),
                        session.assetVersion(), request.sequence(), request.positionMs(), accepted, watched,
                        watched >= 30000, request.state()));
        return new ProgressView(true, request.sequence(), request.positionMs(), watched, watched >= 30000, request.state());
    }

    private EligibleContent eligible(IdentityPrincipal actor, PlaybackStore.Session session) {
        store.lockCatalog(session.contentId());
        var eligible = entitlements.requireEligible(actor, session.profileId(), session.contentId());
        if (!eligible.bindingId().equals(session.bindingId()) || !eligible.assetId().equals(session.assetId())
                || eligible.assetVersion() != session.assetVersion() || eligible.durationSeconds() * 1000L != session.durationMs()) {
            throw DomainException.conflict("MEDIA_BINDING_CHANGED");
        }
        return eligible;
    }

    private void requireLive(PlaybackStore.Session session) {
        if (session.state() == State.ENDED) { throw DomainException.conflict("PLAYBACK_ENDED"); }
        if (!session.expiresAt().isAfter(clock.instant())) { throw DomainException.conflict("PLAYBACK_EXPIRED"); }
    }

    private void validate(Object request) {
        if (request == null || !validator.validate(request).isEmpty()) { throw DomainException.invalid(); }
    }

    private static ProgressView view(PlaybackStore.Session session, boolean accepted) {
        return new ProgressView(accepted, session.sequence(), session.positionMs(), session.watchedMs(),
                session.watchedMs() >= 30000, session.state());
    }
}
