package com.libra.streaming.media.processing.application;

import java.nio.file.Path;
import java.util.function.BooleanSupplier;

/** Inspects one local file. Implementations never expose process output, paths or parser messages. */
public interface MediaProber {
    ProbeReport probe(Path file, BooleanSupplier cancelled) throws InterruptedException;

    /** The tool could not be run or did not finish in time: retryable, says nothing about the input. */
    final class Unavailable extends RuntimeException {
        public Unavailable() { super("PROBE_UNAVAILABLE", null, false, false); }
    }

    /** The tool ran to completion but produced no usable description: a permanent input failure. */
    final class Unreadable extends RuntimeException {
        public Unreadable() { super("PROBE_UNREADABLE", null, false, false); }
    }
}
