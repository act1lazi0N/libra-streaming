package com.libra.streaming.recommendation.ingestion.infrastructure;

import com.libra.streaming.recommendation.freshness.domain.InputTopic;
import com.libra.streaming.recommendation.ingestion.application.EventIntake;
import com.libra.streaming.recommendation.ingestion.infrastructure.IngestionFixtures.Event;
import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.assertThat;

/** Small event-building adapter shared by the preference, candidate and HTTP PostgreSQL suites. */
public final class RecommendationFixtures {
    private final EventIntake intake;

    public RecommendationFixtures(EventIntake intake) { this.intake = intake; }

    public void accept(Event event) {
        assertThat(intake.accept(event.topic(), event.key(), event.json())).isEqualTo(EventIntake.Outcome.APPLIED);
    }

    public UUID title(String kind, UUID parent, String genre, Instant at, String state, boolean published) {
        return title(kind, parent, genre, at, state, published, 120);
    }

    public UUID title(String kind, UUID parent, String genre, Instant at, String state, boolean published,
            int durationSeconds) {
        UUID id = UUID.randomUUID();
        var event = new IngestionFixtures().catalog(id, kind, parent, 1,
                published ? "CatalogPublished" : "CatalogUnpublished", at);
        if (published) {
            ((ObjectNode) event.payload().path("published").path("metadata")).putArray("genres").add(genre);
            if (kind.equals("MOVIE") || kind.equals("EPISODE")) {
                if (state == null) { event.payload().putNull("active"); }
                else {
                    ((ObjectNode) event.payload().path("active")).put("state", state)
                            .put("durationSeconds", durationSeconds);
                }
            }
        }
        accept(event);
        return id;
    }

    public void profile(UUID account, UUID profile, boolean deleted) {
        var event = new IngestionFixtures().template(InputTopic.PROFILES,
                deleted ? "profile-deleted" : "profile-created");
        event.root().put("aggregateId", profile.toString());
        event.payload().put("accountId", account.toString()).put("profileId", profile.toString());
        accept(event);
    }

    public Event playback(UUID account, UUID profile, UUID content, UUID session, long sequence,
            long increment, long total, long position, Instant at) {
        var event = new IngestionFixtures().playback(sequence, increment, total);
        event.root().put("aggregateId", session.toString()).put("occurredAt", at.toString());
        event.payload().put("sessionId", session.toString()).put("accountId", account.toString())
                .put("profileId", profile.toString()).put("contentId", content.toString())
                .put("assetId", session.toString())
                .put("positionMs", position);
        return event;
    }

    public void watch(UUID account, UUID profile, UUID content, long watched, long position, Instant at) {
        accept(playback(account, profile, content, UUID.randomUUID(), 1, watched, watched, position, at));
    }
}
