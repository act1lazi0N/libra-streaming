package com.libra.streaming.core.history.application;

import com.libra.streaming.core.history.domain.ResumeState;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class HistoryUseCase {
    private final HistoryStore store;
    private final Clock clock;

    public HistoryUseCase(HistoryStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    public long opened(UUID profileId, UUID contentId, UUID sessionId, long durationMs) {
        ResumeState previous = store.previous(profileId, contentId);
        long position = previous == null ? 0 : previous.openingPosition(durationMs);
        store.opened(profileId, contentId, sessionId, position, durationMs, clock.instant());
        return position;
    }

    public void accepted(UUID sessionId, long positionMs, long durationMs, Instant now) {
        store.accepted(sessionId, positionMs, ResumeState.completes(positionMs, durationMs), now);
    }

    public List<HistoryOperations.HistoryView> list(UUID accountId, UUID profileId,
            boolean continuing, int limit, int offset) {
        requireOwned(accountId, profileId);
        if (limit < 1 || limit > 100 || offset < 0 || offset > 10000) { throw new Failure("INVALID_REQUEST"); }
        return store.list(profileId, continuing, limit, offset);
    }

    public void clear(UUID accountId, UUID profileId, UUID contentId) {
        requireOwned(accountId, profileId);
        store.clear(profileId, contentId);
    }

    private void requireOwned(UUID accountId, UUID profileId) {
        if (!store.owned(accountId, profileId)) { throw new Failure("NOT_FOUND"); }
    }

    public static final class Failure extends RuntimeException {
        public Failure(String code) { super(code); }
    }
}
