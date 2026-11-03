package com.libra.streaming.media.lifecycle.application;

import java.util.UUID;

/**
 * Deletes exactly named objects and exactly named attempt prefixes. Implementations never list a whole area, never
 * expose storage errors, URLs or bucket names, and refuse a key outside the area it belongs to.
 */
public interface CleanupStorage {
    /** The frozen source key attempt {@code attempt} of this upload reserved; the same rule the worker uses. */
    String sourceKey(UUID uploadId, int attempt);

    /** The output prefix (without a trailing slash) attempt {@code attempt} of this upload reserved. */
    String outputPrefix(UUID uploadId, int attempt);

    /** Deletes the staging object; true if it existed when this call looked. */
    boolean deleteStaging(String key);

    /** Deletes one frozen source object; true if it existed when this call looked. */
    boolean deleteSource(String key);

    /** Deletes at most {@code limit} objects under {@code prefix + "/"}; nothing outside it is listed or deleted. */
    Batch deleteOutput(String prefix, int limit);

    /** Objects deleted, and whether the listing was exhausted (false means more may remain). */
    record Batch(int deleted, boolean exhausted) {}

    /** Transient storage failure; deliberately carries no remote detail. */
    final class Unavailable extends RuntimeException {
        public Unavailable() { super("CLEANUP_STORAGE_UNAVAILABLE", null, false, false); }
    }
}
