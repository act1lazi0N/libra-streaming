package com.libra.streaming.recommendation.bootstrap.application;

import com.libra.streaming.recommendation.bootstrap.domain.SnapshotPages;
import java.util.UUID;

/**
 * Core's bootstrap reads (core-internal-analytics.v1). Implementations validate every page strictly and throw
 * {@link SnapshotUnavailableException} for any transport, status or body failure; a page is never partially used.
 */
public interface CoreSnapshots {
    SnapshotPages.ProfilePage profiles(UUID after, int limit);

    SnapshotPages.CatalogPage catalog(UUID after, int limit);

    SnapshotPages.PlaybackPage playback(String after, int limit);

    /** Core could not be read or answered outside the contract; the run stops and is retried later. */
    final class SnapshotUnavailableException extends RuntimeException {
        public SnapshotUnavailableException(String code) { super(code, null, false, false); }
    }
}
