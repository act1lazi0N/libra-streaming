package com.libra.streaming.media.delivery.application;

import com.libra.streaming.media.delivery.domain.PlaybackGrant;
import com.libra.streaming.media.delivery.domain.StreamName;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Resolves one protected stream request. The object key is derived only from Media's READY record for the asset version
 * the ticket names, after that record is shown to belong to the ticket's content binding, and from a name in the closed
 * {@link StreamName} vocabulary. The browser never supplies a key, prefix, bucket or version. Every opened stream holds
 * an admission permit and must finish within the transfer timeout and before its ticket expires.
 */
public final class StreamDelivery {
    private final DeliverableAssets assets;
    private final StreamObjects objects;
    private final StreamAdmission admission;
    private final Duration transferTimeout;
    private final Clock clock;

    public StreamDelivery(DeliverableAssets assets, StreamObjects objects, Limits limits, Clock clock) {
        this.assets = Objects.requireNonNull(assets);
        this.objects = Objects.requireNonNull(objects);
        this.admission = new StreamAdmission(limits.maxStreams(), limits.maxStreamsPerSession());
        this.transferTimeout = limits.transferTimeout();
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * The opened object, which the caller must close. A name outside the vocabulary, an unknown or not yet READY asset
     * version, or a missing object is {@link NotFound}; a READY record for another content binding is
     * {@link Denied}; storage trouble is {@link StreamObjects.Unavailable}; a session or instance at its stream limit
     * is {@link StreamAdmission.SessionBusy} or {@link StreamAdmission.Busy}. A bad name is refused before admission;
     * storage is touched only after every check.
     */
    public Stream open(PlaybackGrant grant, String requestedName) {
        Objects.requireNonNull(grant);
        var name = StreamName.parse(requestedName).orElseThrow(NotFound::new);
        var permit = admission.admit(grant.sessionId());
        boolean handedOver = false;
        try {
            // The bound starts at admission, so a slow lookup or storage open spends the same budget as the copy, and
            // never reaches past the ticket's expiry: delivery stops when the ticket does.
            var deadline = earliest(clock.instant().plus(transferTimeout), grant.expiresAt());
            var asset = assets.find(grant.assetId(), grant.assetVersion()).orElseThrow(NotFound::new);
            if (!grant.contentId().equals(asset.contentId()) || !grant.bindingId().equals(asset.bindingId())) {
                throw new Denied();
            }
            if (!asset.ready()) { throw new NotFound(); }
            String prefix = asset.outputPrefix();
            // The database guarantees both for READY; a disagreement is a broken record, never a reason to guess a key.
            if (prefix == null || prefix.isBlank() || prefix.endsWith("/")
                    || !(prefix + "/master.m3u8").equals(asset.masterKey())) {
                throw new StreamObjects.Unavailable();
            }
            var body = objects.open(prefix + "/" + name.value(), name.kind().maxBytes()).orElseThrow(NotFound::new);
            var stream = new Stream(name, body, permit, clock, deadline);
            handedOver = true;
            return stream;
        } finally {
            // Any refusal or failure, an Error included, gives the share back; an opened stream releases it on close.
            if (!handedOver) { permit.close(); }
        }
    }

    private static Instant earliest(Instant a, Instant b) { return a.isBefore(b) ? a : b; }

    /** Stream limits: in total and per playback session on this instance, and the longest one response may take. */
    public record Limits(int maxStreams, int maxStreamsPerSession, Duration transferTimeout) {
        public Limits {
            if (maxStreams < 1 || maxStreamsPerSession < 1 || maxStreamsPerSession > maxStreams
                    || transferTimeout == null || transferTimeout.compareTo(Duration.ofSeconds(1)) < 0
                    || transferTimeout.compareTo(Duration.ofMinutes(10)) > 0) {
                throw new IllegalArgumentException("Stream delivery limits");
            }
        }
    }

    /** One opened stream object: its name, length and a single bounded pass over its bytes. */
    public static final class Stream implements AutoCloseable {
        private final StreamName name;
        private final StreamObjects.Opened body;
        private final StreamAdmission.Permit permit;
        private final Clock clock;
        private final Instant deadline;

        Stream(StreamName name, StreamObjects.Opened body, StreamAdmission.Permit permit, Clock clock,
                Instant deadline) {
            this.name = name;
            this.body = body;
            this.permit = permit;
            this.clock = clock;
            this.deadline = deadline;
        }

        public StreamName name() { return name; }
        public String contentType() { return name.kind().contentType(); }
        public long length() { return body.length(); }

        /**
         * Copies the whole object to {@code sink}. Once the deadline has passed no further byte is written and the
         * copy fails with {@link Expired}; storage failures and a gone client are reported as by
         * {@link StreamObjects.Opened#transferTo}.
         */
        public void transferTo(OutputStream sink) throws IOException {
            body.transferTo(new OutputStream() {
                @Override public void write(int value) throws IOException {
                    check();
                    sink.write(value);
                }

                @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                    check();
                    sink.write(bytes, offset, length);
                }

                @Override public void flush() throws IOException { sink.flush(); }
            });
        }

        private void check() {
            if (!clock.instant().isBefore(deadline)) { throw new Expired(); }
        }

        /** Releases the object (aborting an unfinished one) and then the admission permit. */
        @Override
        public void close() {
            try { body.close(); } finally { permit.close(); }
        }
    }

    /** Nothing deliverable under that name for this grant. Carries no detail. */
    public static final class NotFound extends RuntimeException {
        public NotFound() { super("NOT_FOUND", null, false, false); }
    }

    /** The ticket names an asset version that Media records for another content binding. */
    public static final class Denied extends RuntimeException {
        public Denied() { super("ACCESS_DENIED", null, false, false); }
    }

    /** The response outlived the transfer timeout; the player was too slow or stopped reading. */
    public static final class Expired extends RuntimeException {
        public Expired() { super("STREAM_TRANSFER_EXPIRED", null, false, false); }
    }
}
