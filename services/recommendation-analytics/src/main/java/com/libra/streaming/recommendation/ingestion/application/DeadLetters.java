package com.libra.streaming.recommendation.ingestion.application;

import com.libra.streaming.recommendation.ingestion.domain.DeadLetter.Captured;
import com.libra.streaming.recommendation.ingestion.domain.DeadLetter.Held;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** All operations join the caller's transaction. */
public interface DeadLetters {
    void capture(Captured record);
    /** HELD rows in (receivedAt, id) order strictly after {@code after}, or from the start when it is null. */
    List<Held> held(Held after, int limit);
    int countHeld();
    void redriven(UUID id, Instant at);
    void attemptFailed(UUID id, String code);
}
