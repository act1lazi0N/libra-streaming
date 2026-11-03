package com.libra.streaming.media.processing.application;

import java.util.Optional;
import java.util.function.BooleanSupplier;

/** Reads the already-frozen private source into scratch; staging is never reachable through this port. */
public interface SourceReader {
    /**
     * Streams the frozen object into a scratch file, reading at most {@code expectedBytes + 1} bytes. Empty
     * means the object does not exist. Throws {@link SourceStorage.Unavailable} on transient faults.
     */
    Optional<SourceStorage.StagedCopy> open(String frozenKey, long expectedBytes, BooleanSupplier cancelled)
            throws InterruptedException;
}
