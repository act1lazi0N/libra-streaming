package com.libra.streaming.core.history.application;

import com.libra.streaming.core.identity.IdentityPrincipal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface HistoryOperations {
    long opened(UUID profileId, UUID contentId, UUID sessionId, long durationMs);

    void accepted(UUID sessionId, long positionMs, long durationMs, Instant now);

    List<HistoryView> list(IdentityPrincipal actor, UUID profileId, boolean continuing, int limit, int offset);

    void clear(IdentityPrincipal actor, UUID profileId, UUID contentId);

    record HistoryView(UUID contentId, String title, long positionMs, long durationMs,
            boolean completed, Instant updatedAt) {}
}
