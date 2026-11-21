package com.libra.streaming.recommendation.preferences.domain;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Accepted watched time of one profile on one watched item (a movie or an episode) inside the interest window, with
 * what the catalog currently says about it.
 *
 * @param titleId the movie or series the item belongs to; null while its parent chain is unknown
 * @param durationMs the item's current playable duration; null when the catalog has no active asset for it
 * @param titleGenres the genres of the title's published revision; empty when it has none or is unpublished
 */
public record WatchedItem(UUID itemId, UUID titleId, Long durationMs, List<String> titleGenres, long watchedMs) {
    public WatchedItem {
        Objects.requireNonNull(itemId, "itemId");
        titleGenres = List.copyOf(titleGenres);
        if (watchedMs < 0) { throw new IllegalArgumentException("watchedMs"); }
        if (durationMs != null && durationMs < 1) { throw new IllegalArgumentException("durationMs"); }
    }
}
