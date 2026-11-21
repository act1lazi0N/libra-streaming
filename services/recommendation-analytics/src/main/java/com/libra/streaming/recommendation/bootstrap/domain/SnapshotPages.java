package com.libra.streaming.recommendation.bootstrap.domain;

import com.libra.streaming.recommendation.catalog.domain.CatalogNode;
import java.util.List;
import java.util.UUID;

/** Validated pages of core-internal-analytics.v1. A null {@code next} means the source is exhausted. */
public final class SnapshotPages {
    private SnapshotPages() {}

    public record ProfileItem(UUID profileId, UUID accountId, long version, boolean deleted) {}

    public record ProfilePage(List<ProfileItem> items, UUID next) {
        public ProfilePage { items = List.copyOf(items); }
    }

    /** Catalog items are already in the read model's shape: the same version rule applies to them. */
    public record CatalogPage(List<CatalogNode> items, UUID next) {
        public CatalogPage { items = List.copyOf(items); }
    }

    /**
     * @param events each replayed event as the JSON text of one {@code core.playback.v1} record; it goes through the
     *        same decoder, receipts and projection as a live record
     */
    public record PlaybackPage(List<ReplayedEvent> events, String next) {
        public PlaybackPage { events = List.copyOf(events); }
    }

    /** @param key the aggregate ID, the Kafka key Core would have published the event with */
    public record ReplayedEvent(String key, String json) {}
}
