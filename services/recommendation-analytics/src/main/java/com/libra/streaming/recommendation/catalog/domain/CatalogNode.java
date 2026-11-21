package com.libra.streaming.recommendation.catalog.domain;

import com.libra.streaming.recommendation.ingestion.domain.CatalogChange;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The publication state of one catalog node at {@code version}; a newer version replaces it as a whole.
 *
 * @param publishedAt time of the latest publication; V1 events carry no {@code publishedAt}, so the
 *        {@code CatalogPublished} event's {@code occurredAt} stands for it (RA01.1)
 * @param inheritsPublishedAt true when this state does not say when publication happened (an availability change):
 *        the stored time is kept
 */
public record CatalogNode(UUID contentId, long version, CatalogChange.Kind kind, UUID parentId, Integer ordinal,
        CatalogChange.Revision published, CatalogChange.Binding active, Instant publishedAt, boolean inheritsPublishedAt,
        Instant changedAt) {
    public CatalogNode {
        Objects.requireNonNull(contentId, "contentId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(changedAt, "changedAt");
        if (version < 1) { throw new IllegalArgumentException("version"); }
        if (published == null && (active != null || publishedAt != null || inheritsPublishedAt)) {
            throw new IllegalArgumentException("An unpublished node has no binding or publication time");
        }
    }

    public static CatalogNode of(CatalogChange change) {
        var envelope = change.envelope();
        return new CatalogNode(change.contentId(), envelope.aggregateVersion(), change.kind(), change.parentId(),
                change.ordinal(), change.published(), change.active(),
                change.type() == CatalogChange.Type.PUBLISHED ? envelope.occurredAt() : null,
                change.type() == CatalogChange.Type.AVAILABILITY_CHANGED, envelope.occurredAt());
    }

    public boolean isPublished() { return published != null; }
}
