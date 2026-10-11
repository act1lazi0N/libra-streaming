package com.libra.streaming.media.processing.application;

import com.libra.streaming.media.processing.domain.JobLease;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/** Private object access for HLS output. Implementations never expose storage errors, URLs or bucket names. */
public interface OutputStorage {
    /**
     * The private prefix reserved for one claim's output, without a trailing slash. The attempt number makes a late
     * object from an old claim unable to land under the prefix a successor selects.
     */
    String prefix(JobLease lease);

    /** Writes one local workspace file to {@code key}, which must lie under a {@link #prefix(JobLease)}. */
    void store(String key, Path file, String contentType);

    /**
     * Re-reads a stored object with bounded streaming, reading at most {@code expectedBytes + 1} bytes. Empty means
     * the object does not exist.
     */
    Optional<Readback> read(String key, long expectedBytes, BooleanSupplier cancelled) throws InterruptedException;

    /** Bytes actually read back and the SHA-256 of exactly those bytes. */
    record Readback(long bytes, String sha256) {}

    /** Transient storage failure; deliberately carries no remote detail. */
    final class Unavailable extends RuntimeException {
        public Unavailable() { super("OUTPUT_STORAGE_UNAVAILABLE", null, false, false); }
    }
}
