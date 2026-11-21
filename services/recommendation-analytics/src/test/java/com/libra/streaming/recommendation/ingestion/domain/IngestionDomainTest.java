package com.libra.streaming.recommendation.ingestion.domain;

import com.libra.streaming.recommendation.catalog.domain.CatalogNode;
import com.libra.streaming.recommendation.playback.domain.SessionTotals;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class IngestionDomainTest {
    static final UUID ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    static final UUID OTHER = UUID.fromString("22222222-2222-4222-8222-222222222222");
    static final Instant NOW = Instant.parse("2026-11-16T00:00:00Z");
    static Envelope envelope(long version) { return new Envelope(OTHER, "CatalogPublished", ID, version, NOW, OTHER); }
    static CatalogChange.Revision revision() {
        return new CatalogChange.Revision(1, new CatalogChange.Metadata("title", "", List.of("drama"), 2026, "en",
                List.of(), List.of(), "FREE"), NOW);
    }
    static CatalogChange catalog(CatalogChange.Type type, CatalogChange.Kind kind, UUID parent, Integer ordinal,
            CatalogChange.Revision revision, CatalogChange.Binding binding) {
        return new CatalogChange(envelope(1), type, ID, kind, parent, ordinal, revision, binding);
    }
    static void semantic(Runnable action, String code) {
        assertThatThrownBy(action::run).isInstanceOf(EventRejection.class)
                .hasMessage("SEMANTIC_INVALID:" + code);
    }

    @Test
    void catalogNodeMapsPublicationAvailabilityAndUnpublication() {
        for (var type : CatalogChange.Type.values()) {
            var change = catalog(type, CatalogChange.Kind.MOVIE, null, null,
                    type == CatalogChange.Type.UNPUBLISHED ? null : revision(), null);
            var node = CatalogNode.of(change);
            assertThat(node.contentId()).isEqualTo(ID);
            assertThat(node.version()).isEqualTo(1);
            assertThat(node.changedAt()).isEqualTo(NOW);
            assertThat(node.publishedAt()).isEqualTo(type == CatalogChange.Type.PUBLISHED ? NOW : null);
            assertThat(node.inheritsPublishedAt()).isEqualTo(type == CatalogChange.Type.AVAILABILITY_CHANGED);
            assertThat(node.isPublished()).isEqualTo(type != CatalogChange.Type.UNPUBLISHED);
        }
    }

    @Test
    void publicationShapeAndBindingIdentityAreEnforced() {
        for (var type : List.of(CatalogChange.Type.PUBLISHED, CatalogChange.Type.AVAILABILITY_CHANGED)) {
            semantic(() -> catalog(type, CatalogChange.Kind.MOVIE, null, null, null, null), "PUBLICATION_SHAPE");
        }
        semantic(() -> catalog(CatalogChange.Type.UNPUBLISHED, CatalogChange.Kind.MOVIE, null, null, revision(), null),
                "PUBLICATION_SHAPE");
        var binding = new CatalogChange.Binding(ID, OTHER, ID, 1, 0, "READY", 1);
        semantic(() -> catalog(CatalogChange.Type.PUBLISHED, CatalogChange.Kind.MOVIE, null, null, revision(), binding),
                "BINDING_CONTENT");
        semantic(() -> catalog(CatalogChange.Type.UNPUBLISHED, CatalogChange.Kind.MOVIE, null, null, null, binding),
                "PUBLICATION_SHAPE");
    }

    @Test
    void catalogTreePositionIsEnforcedForEveryKind() {
        for (var kind : CatalogChange.Kind.values()) {
            var valid = catalog(CatalogChange.Type.PUBLISHED, kind, kind.root() ? null : OTHER,
                    kind.root() ? null : 1, revision(), null);
            assertThat(valid.kind()).isEqualTo(kind);
            semantic(() -> catalog(CatalogChange.Type.PUBLISHED, kind, kind.root() ? OTHER : null,
                    kind.root() ? 1 : null, revision(), null), "TREE_POSITION");
            semantic(() -> catalog(CatalogChange.Type.PUBLISHED, kind, kind.root() ? null : OTHER,
                    kind.root() ? 1 : null, revision(), null), "TREE_POSITION");
        }
    }

    @Test
    void profileAndCatalogAggregateIdentityMustMatch() {
        semantic(() -> new ProfileChange(envelope(1), ProfileChange.Type.CREATED, ID, OTHER),
                "AGGREGATE_NOT_PAYLOAD_IDENTITY");
        semantic(() -> new CatalogChange(envelope(1), CatalogChange.Type.PUBLISHED, OTHER, CatalogChange.Kind.MOVIE,
                null, null, revision(), null), "AGGREGATE_NOT_PAYLOAD_IDENTITY");
        for (var type : ProfileChange.Type.values()) {
            assertThat(new ProfileChange(envelope(1), type, OTHER, ID).type()).isEqualTo(type);
        }
    }

    static PlaybackProgress playback(UUID session, long version, long sequence, long increment, long total, boolean qualified) {
        return new PlaybackProgress(envelope(version), OTHER, OTHER, session, OTHER, OTHER, 1, sequence, 0,
                increment, total, qualified, PlaybackProgress.State.PLAYING);
    }

    @Test
    void playbackSemanticRulesAndQualificationBoundary() {
        semantic(() -> playback(OTHER, 1, 1, 0, 0, false), "AGGREGATE_NOT_PAYLOAD_IDENTITY");
        semantic(() -> playback(ID, 2, 1, 0, 0, false), "VERSION_NOT_SEQUENCE");
        semantic(() -> playback(ID, 1, 1, 2, 1, false), "INCREMENT_EXCEEDS_TOTAL");
        semantic(() -> playback(ID, 1, 1, 1, 29999, true), "QUALIFIED_MISMATCH");
        semantic(() -> playback(ID, 1, 1, 1, 30000, false), "QUALIFIED_MISMATCH");
        assertThat(playback(ID, 1, 1, 0, 29999, false).qualified()).isFalse();
        assertThat(playback(ID, 1, 1, 30000, 30000, true).qualified()).isTrue();
    }

    @Test
    void totalsDetectGapsAndClampMissingTimeAtZero() {
        var incomplete = totals(1, 2, 10, 30);
        assertThat(incomplete.complete()).isFalse();
        assertThat(incomplete.missingWatchedMs()).isEqualTo(20);
        assertThat(totals(2, 2, 30, 30).complete()).isTrue();
        assertThat(totals(2, 2, 30, 30).missingWatchedMs()).isZero();
        assertThat(totals(1, 2, 40, 30).missingWatchedMs()).isZero();
    }

    static SessionTotals totals(int count, long max, long accepted, long total) {
        return new SessionTotals(ID, OTHER, OTHER, OTHER, count, accepted, max, total, null, null, NOW, NOW);
    }
}

