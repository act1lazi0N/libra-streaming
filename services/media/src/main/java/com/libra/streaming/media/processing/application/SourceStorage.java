package com.libra.streaming.media.processing.application;

import com.libra.streaming.media.processing.domain.JobLease;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/** Private object access for source freezing. Implementations never expose storage errors or URLs. */
public interface SourceStorage {
    /**
     * Streams the staging object into a private scratch file, reading at most {@code declaredBytes + 1}
     * bytes. Empty means the object does not exist; a short or long copy is reported, not hidden.
     */
    Optional<StagedCopy> stage(String stagingKey, long declaredBytes, BooleanSupplier cancelled)
            throws InterruptedException;

    /** Private key reserved for one claim. It is outside the staging area addressed by upload grants. */
    String frozenKey(JobLease lease);

    void store(String frozenKey, StagedCopy copy);

    /** Re-reads stored bytes with bounded streaming, reading at most {@code expectedBytes + 1} bytes. */
    Optional<Digest> digest(String frozenKey, long expectedBytes, BooleanSupplier cancelled)
            throws InterruptedException;

    /** Transient storage or scratch-disk failure; deliberately carries no remote detail. */
    final class Unavailable extends RuntimeException {
        public Unavailable() { super("SOURCE_STORAGE_UNAVAILABLE", null, false, false); }
    }

    /** Bytes counted and hashed while they were written to the scratch file. Closing deletes the file. */
    record StagedCopy(Path file, long bytes, String sha256) implements AutoCloseable {
        @Override
        public void close() {
            try { Files.deleteIfExists(file); }
            catch (IOException ignored) { /* A leftover scratch file is reconciled by the later cleanup milestone. */ }
        }
    }

    record Digest(long bytes, String sha256) {}
}
