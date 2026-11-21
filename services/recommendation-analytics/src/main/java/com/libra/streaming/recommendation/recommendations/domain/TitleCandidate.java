package com.libra.streaming.recommendation.recommendations.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A movie or series the catalog projection says Core could show: published, and playable (a movie with a READY
 * asset; a series with a published season holding a published episode with a READY asset). Core rechecks both.
 *
 * @param publishedAt time of the title's latest publication; null when no {@code CatalogPublished} carried it
 */
public record TitleCandidate(UUID titleId, Kind kind, List<String> genres, Instant publishedAt) {
    public enum Kind { MOVIE, SERIES }

    public TitleCandidate {
        Objects.requireNonNull(titleId, "titleId");
        Objects.requireNonNull(kind, "kind");
        genres = List.copyOf(genres);
    }
}
