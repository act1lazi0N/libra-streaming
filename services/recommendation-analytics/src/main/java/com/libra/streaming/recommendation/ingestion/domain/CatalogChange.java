package com.libra.streaming.recommendation.ingestion.domain;

import com.libra.streaming.recommendation.freshness.domain.InputTopic;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** {@code core.catalog.v1}: the publication state of one content node at {@code aggregateVersion}. */
public record CatalogChange(Envelope envelope, Type type, UUID contentId, Kind kind, UUID parentId, Integer ordinal,
        Revision published, Binding active) implements CoreEvent {
    public enum Type {
        PUBLISHED("CatalogPublished"), UNPUBLISHED("CatalogUnpublished"), AVAILABILITY_CHANGED("CatalogAvailabilityChanged");

        private final String wireName;

        Type(String wireName) { this.wireName = wireName; }

        public String wireName() { return wireName; }

        public static Type fromWireName(String wireName) {
            for (Type type : values()) { if (type.wireName.equals(wireName)) { return type; } }
            throw EventRejection.schema("EVENT_TYPE");
        }
    }

    public enum Kind {
        MOVIE, SERIES, SEASON, EPISODE;

        /** Movies and series are roots; seasons and episodes always hang below a parent. */
        public boolean root() { return this == MOVIE || this == SERIES; }
    }

    public record Revision(long revision, Metadata metadata, Instant createdAt) {
        public Revision { Objects.requireNonNull(metadata, "metadata"); Objects.requireNonNull(createdAt, "createdAt"); }
    }

    public record Metadata(String title, String description, List<String> genres, int releaseYear, String language,
            List<String> credits, List<String> imageReferences, String accessTier) {
        public Metadata {
            genres = List.copyOf(genres);
            credits = List.copyOf(credits);
            imageReferences = List.copyOf(imageReferences);
        }
    }

    public record Binding(UUID id, UUID contentId, UUID assetId, long assetVersion, long projectionVersion,
            String state, Integer durationSeconds) {}

    public CatalogChange {
        Objects.requireNonNull(envelope, "envelope");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(contentId, "contentId");
        Objects.requireNonNull(kind, "kind");
        envelope.requireAggregate(contentId);
        if (type == Type.UNPUBLISHED ? published != null || active != null : published == null) {
            throw EventRejection.semantic("PUBLICATION_SHAPE");
        }
        // Core's own table constraint; a node that changes shape cannot be placed in the tree.
        if (kind.root() != (parentId == null) || kind.root() != (ordinal == null)) {
            throw EventRejection.semantic("TREE_POSITION");
        }
        if (active != null && !active.contentId().equals(contentId)) { throw EventRejection.semantic("BINDING_CONTENT"); }
    }

    @Override
    public InputTopic topic() { return InputTopic.CATALOG; }
}
