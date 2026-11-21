package com.libra.streaming.recommendation.convergence;

import com.libra.streaming.recommendation.freshness.domain.InputTopic;
import com.libra.streaming.recommendation.ingestion.infrastructure.IngestionFixtures;
import com.libra.streaming.recommendation.ingestion.infrastructure.IngestionFixtures.Event;
import com.libra.streaming.recommendation.ingestion.infrastructure.JsonCoreEventDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.assertThat;

/** Synthetic, reproducible facts; expectations are folded from JSON without using any projection code. */
final class CanonicalEvents {
    static final long SEED = 25021116L;
    static final Instant BASE = Instant.parse("2026-09-07T10:15:30Z");
    record Delivery(InputTopic topic, String key, String json, UUID eventId) {}
    final List<Delivery> ordered;
    final Map<UUID, Long> accepted;
    final Map<UUID, Long> qualifying;
    final Set<String> payloadIds;
    final UUID movie;

    private CanonicalEvents(List<Event> events, UUID movie) {
        this.movie = movie;
        var decoder = new JsonCoreEventDecoder();
        var deliveries = new ArrayList<Delivery>();
        var sums = new TreeMap<UUID, Long>();
        var qualifications = new TreeMap<UUID, Long>();
        var ids = new TreeSet<String>();
        var times = new HashMap<String, Instant>();
        for (Event event : events) {
            decoder.decode(event.topic(), event.key(), event.json());
            Instant at = Instant.parse(event.root().path("occurredAt").stringValue());
            Instant previous = times.put(event.key(), at);
            assertThat(previous == null || at.isAfter(previous)).as("aggregate time increases").isTrue();
            deliveries.add(new Delivery(event.topic(), event.key(), event.json(), event.id()));
            collectIds(event.payload(), ids);
            if (event.topic() == InputTopic.PLAYBACK) {
                var p = event.payload();
                UUID session = UUID.fromString(p.path("sessionId").stringValue());
                long sum = sums.merge(session, p.path("acceptedWatchedMs").longValue(), Long::sum);
                assertThat(p.path("totalWatchedMs").longValue()).as("running canonical total").isEqualTo(sum);
                if (sum >= 30000) { qualifications.putIfAbsent(session, p.path("sequence").longValue()); }
            }
        }
        ordered = List.copyOf(deliveries);
        accepted = Map.copyOf(sums);
        qualifying = Map.copyOf(qualifications);
        payloadIds = Set.copyOf(ids);
        assertThat(ordered.stream().map(Delivery::eventId).distinct().count()).isEqualTo(ordered.size());
        assertThat(accepted).hasSize(6);
        assertThat(qualifying).hasSize(6);
    }

    static CanonicalEvents create() {
        var f = new IngestionFixtures();
        // Replace every UUID placeholder, including IDs nested inside catalog bindings.
        for (var entry : f.values.entrySet()) {
            try { UUID.fromString(entry.getValue()); entry.setValue(id(entry.getKey()).toString()); }
            catch (IllegalArgumentException ignored) { /* Non-ID placeholder. */ }
        }
        var events = new ArrayList<Event>();
        UUID series = id("S"), season = id("season"), e1 = id("E1"), e2 = id("E2"), movie = id("M");
        for (int p = 1; p <= 3; p++) {
            profile(events, f, p, 1, "profile-created", 1);
            if (p == 1) { profile(events, f, p, 2, "profile-updated", 2); }
        }
        events.add(f.catalog(series, "SERIES", null, 1, "CatalogPublished", BASE.plusSeconds(3)));
        events.add(f.catalog(season, "SEASON", series, 1, "CatalogPublished", BASE.plusSeconds(4)));
        events.add(f.catalog(e1, "EPISODE", season, 1, "CatalogPublished", BASE.plusSeconds(5)));
        events.add(f.catalog(e2, "EPISODE", season, 1, "CatalogPublished", BASE.plusSeconds(6)));
        for (int v = 2; v <= 6; v++) {
            String type = v == 4 ? "CatalogUnpublished" : v == 3 || v == 6
                    ? "CatalogAvailabilityChanged" : "CatalogPublished";
            var event = f.catalog(movie, "MOVIE", null, v, type, BASE.plusSeconds(10 + v));
            if (v == 3) { ((ObjectNode) event.payload().path("active")).put("state", "FAILED").putNull("durationSeconds"); }
            events.add(event);
        }
        // Six heartbeats: 10s, 10s, a zero increment seek, qualification, then two later facts.
        for (int s = 0; s < 6; s++) {
            if (s == 5) { profile(events, f, 3, 3, "profile-deleted", 200); }
            UUID session = id("session-" + s);
            int profile = s >= 4 ? 3 : s % 2 + 1;
            long total = 0;
            for (int q = 1; q <= 6; q++) {
                long increment = q == 3 ? 0 : 10000;
                total += increment;
                var event = f.playback(q, increment, total);
                event.root().put("aggregateId", session.toString()).put("occurredAt",
                        BASE.plusSeconds((s == 5 ? 300 : 30 + s * 20) + q).toString());
                event.payload().put("sessionId", session.toString()).put("profileId", id("P" + profile).toString())
                        .put("accountId", id("A" + (profile == 3 ? 2 : 1)).toString())
                        .put("contentId", List.of(e1, e2, movie, id("unknown"), e1, movie).get(s).toString())
                        .put("state", q == 3 ? "SEEKING" : "PLAYING");
                events.add(event);
            }
        }
        for (int i = 0; i < events.size(); i++) {
            events.get(i).root().put("eventId", id("event-" + i).toString())
                    .put("correlationId", id("correlation-" + i).toString());
        }
        return new CanonicalEvents(events, movie);
    }

    private static void profile(List<Event> events, IngestionFixtures f, int p, long version, String fixture, long second) {
        var event = f.template(InputTopic.PROFILES, fixture);
        event.root().put("aggregateId", id("P" + p).toString()).put("aggregateVersion", version)
                .put("occurredAt", BASE.plusSeconds(second).toString());
        event.payload().put("profileId", id("P" + p).toString()).put("accountId", id("A" + (p == 3 ? 2 : 1)).toString());
        events.add(event);
    }

    static UUID id(String name) {
        return UUID.nameUUIDFromBytes((SEED + ":" + name).getBytes(StandardCharsets.UTF_8));
    }

    private static void collectIds(JsonNode node, Set<String> ids) {
        if (node.isObject()) {
            node.properties().forEach(entry -> {
                if (Set.of("accountId", "profileId", "sessionId", "contentId").contains(entry.getKey())
                        && entry.getValue().isString()) { ids.add(entry.getValue().stringValue()); }
                collectIds(entry.getValue(), ids);
            });
        } else if (node.isArray()) { node.values().forEach(value -> collectIds(value, ids)); }
    }

    List<Delivery> shuffled(long seed) {
        var random = new Random(seed);
        var result = new ArrayList<Delivery>();
        ordered.forEach(event -> { for (int n = 1 + random.nextInt(3); n > 0; n--) { result.add(event); } });
        Collections.shuffle(result, random);
        return result;
    }

    static List<Event> invalid() {
        var f = new IngestionFixtures();
        return List.of(f.template(InputTopic.PLAYBACK, "playback-missing-total"),
                f.template(InputTopic.PROFILES, "profile-extra-display-name"),
                f.template(InputTopic.CATALOG, "catalog-missing-active"),
                f.template(InputTopic.PLAYBACK, "playback-increment-exceeds-total"),
                f.template(InputTopic.PLAYBACK, "playback-qualified-below-threshold"));
    }
}
