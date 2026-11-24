package com.libra.streaming.recommendation.statistics.application;

import java.util.function.Supplier;

/**
 * Runs several reads against one database snapshot (read-only, repeatable read), so values read by separate
 * statements agree with each other.
 */
public interface ConsistentReads {
    <T> T read(Supplier<T> work);
}
