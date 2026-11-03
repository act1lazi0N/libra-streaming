package com.libra.streaming.media.delivery.application;

import java.io.OutputStream;
import java.util.Optional;

/** Read-only access to the private HLS area. Implementations never expose storage errors, URLs or bucket names. */
public interface StreamObjects {
    /**
     * Opens one stored object for streaming. Empty means it does not exist. An object larger than {@code maxBytes},
     * or one whose length storage does not state, is refused as {@link Unavailable}: nothing is buffered to find out.
     */
    Optional<Opened> open(String key, long maxBytes);

    /** An open object: its exact length and a single pass over its bytes. Closing an unfinished one aborts it. */
    interface Opened extends AutoCloseable {
        long length();

        /**
         * Copies exactly {@link #length()} bytes to {@code sink}. A storage failure, or a body shorter than the stated
         * length, is {@link Unavailable}; a failure to write to the sink (the client went away) is a
         * {@link java.io.IOException}.
         */
        void transferTo(OutputStream sink) throws java.io.IOException;

        @Override void close();
    }

    /** Transient or integrity failure of storage; deliberately carries no remote detail. */
    final class Unavailable extends RuntimeException {
        public Unavailable() { super("STREAM_STORAGE_UNAVAILABLE", null, false, false); }
    }
}
