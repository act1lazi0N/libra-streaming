package com.libra.streaming.core.history.application;

import com.libra.streaming.core.history.domain.ResumeState;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface HistoryStore {
    ResumeState previous(UUID profileId, UUID contentId);

    void opened(UUID profileId, UUID contentId, UUID sessionId, long positionMs, long durationMs, Instant now);

    void accepted(UUID sessionId, long positionMs, boolean completed, Instant now);

    boolean owned(UUID accountId, UUID profileId);

    List<HistoryOperations.HistoryView> list(UUID profileId, boolean continuing, int limit, int offset);

    void clear(UUID profileId, UUID contentId);
}
