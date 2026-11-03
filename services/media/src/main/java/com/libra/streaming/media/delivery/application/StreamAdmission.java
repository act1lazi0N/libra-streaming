package com.libra.streaming.media.delivery.application;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bounds how many protected streams this instance serves at once, in total and per playback session. A stream holds a
 * request thread and a storage connection for as long as the player reads, so admission never waits: a request over
 * either limit is refused at once and the player retries. Counts are per Media instance and live only in memory.
 */
public final class StreamAdmission {
    private final int maxStreamsPerSession;
    private final Semaphore streams;
    private final ConcurrentHashMap<UUID, Integer> sessions = new ConcurrentHashMap<>();

    public StreamAdmission(int maxStreams, int maxStreamsPerSession) {
        if (maxStreams < 1 || maxStreamsPerSession < 1 || maxStreamsPerSession > maxStreams) {
            throw new IllegalArgumentException("Stream limits");
        }
        this.maxStreamsPerSession = maxStreamsPerSession;
        this.streams = new Semaphore(maxStreams);
    }

    /**
     * A permit the caller must close exactly when the stream ends. The session's own limit is {@link SessionBusy}; the
     * instance's is {@link Busy}. A refused request holds nothing.
     */
    public Permit admit(UUID session) {
        Objects.requireNonNull(session);
        var admitted = new boolean[1];
        sessions.compute(session, (id, held) -> {
            int count = held == null ? 0 : held;
            if (count >= maxStreamsPerSession) { return held; }
            admitted[0] = true;
            return count + 1;
        });
        if (!admitted[0]) { throw new SessionBusy(); }
        if (!streams.tryAcquire()) {
            leave(session);
            throw new Busy();
        }
        return new Permit(session);
    }

    /** Streams currently admitted for a session; zero once all of them ended (no entry is kept). */
    int held(UUID session) { return sessions.getOrDefault(session, 0); }

    int available() { return streams.availablePermits(); }

    private void leave(UUID session) {
        sessions.computeIfPresent(session, (id, held) -> held <= 1 ? null : held - 1);
    }

    /** One admitted stream. Closing it more than once releases it once. */
    public final class Permit implements AutoCloseable {
        private final UUID session;
        private final AtomicBoolean released = new AtomicBoolean();

        private Permit(UUID session) { this.session = session; }

        @Override
        public void close() {
            if (released.compareAndSet(false, true)) {
                streams.release();
                leave(session);
            }
        }
    }

    /** The session already holds its share of streams. */
    public static final class SessionBusy extends RuntimeException {
        public SessionBusy() { super("STREAM_SESSION_BUSY", null, false, false); }
    }

    /** This instance is serving as many streams as it allows. */
    public static final class Busy extends RuntimeException {
        public Busy() { super("STREAM_CAPACITY_EXHAUSTED", null, false, false); }
    }
}
