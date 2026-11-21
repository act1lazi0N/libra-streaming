package com.libra.streaming.recommendation.ingestion.infrastructure;

import com.libra.streaming.recommendation.freshness.domain.InputTopic;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Fixture loader copied from CoreEventContractTest; independent of the Core module. */
public final class IngestionFixtures {
    public static final JsonMapper JSON = JsonMapper.builder().build();
    static final Path DIRECTORY = directory();
    static final JsonNode MANIFEST = read(DIRECTORY.resolve("manifest.json"));
    static final Instant BASE = Instant.parse("2026-09-07T10:15:30Z");
    public final Map<String, String> values = new HashMap<>();
    final UUID account = UUID.randomUUID(), profile = UUID.randomUUID(), content = UUID.randomUUID(), session = UUID.randomUUID();

    public IngestionFixtures() {
        MANIFEST.path("placeholders").properties().forEach(entry -> values.put(entry.getKey(), entry.getValue().stringValue()));
        for (String name : List.of("eventId", "correlationId", "assetId", "bindingId", "parentId")) { values.put(name, UUID.randomUUID().toString()); }
        values.put("accountId", account.toString()); values.put("profileId", profile.toString());
        values.put("contentId", content.toString()); values.put("sessionId", session.toString());
        values.put("occurredAt", BASE.toString()); values.put("createdAt", BASE.toString());
    }

    public record Event(InputTopic topic, ObjectNode root) {
        public String key() { return root.path("aggregateId").stringValue(); }
        public UUID id() { return UUID.fromString(root.path("eventId").stringValue()); }
        public String json() { return root.toString(); }
        public ObjectNode payload() { return (ObjectNode) root.path("payload"); }
        Event copy() { return new Event(topic, root.deepCopy()); }
        Event fresh() { var copy = copy(); copy.root.put("eventId", UUID.randomUUID().toString()); return copy; }
    }

    public Event template(InputTopic topic, String file) {
        try {
            String text = Files.readString(DIRECTORY.resolve("events/" + file + ".json"));
            var matcher = Pattern.compile("\\{\\{([A-Za-z][A-Za-z0-9]*)}}").matcher(text);
            var result = new StringBuilder();
            while (matcher.find()) {
                String sample = values.get(matcher.group(1));
                if (sample == null) { throw new IllegalStateException("Fixture substitution missing"); }
                matcher.appendReplacement(result, java.util.regex.Matcher.quoteReplacement(sample));
            }
            var event = new Event(topic, (ObjectNode) JSON.readTree(matcher.appendTail(result).toString()));
            event.root.put("eventId", UUID.randomUUID().toString());
            event.root.put("correlationId", UUID.randomUUID().toString());
            return event;
        } catch (java.io.IOException failure) { throw new IllegalStateException("Fixture read failed"); }
    }

    public Event catalog(UUID id, String kind, UUID parent, long version, String type, Instant at) {
        var event = template(InputTopic.CATALOG, "catalog-movie-published");
        event.root.put("aggregateId", id.toString()).put("aggregateVersion", version).put("eventType", type).put("occurredAt", at.toString());
        var payload = event.payload(); payload.put("contentId", id.toString()).put("kind", kind);
        if (parent == null) { payload.putNull("parentId"); payload.putNull("ordinal"); }
        else { payload.put("parentId", parent.toString()).put("ordinal", 1); }
        ((ObjectNode) payload.path("active")).put("contentId", id.toString());
        if (kind.equals("SERIES") || kind.equals("SEASON")) { payload.putNull("active"); }
        if (type.equals("CatalogUnpublished")) { payload.putNull("published"); payload.putNull("active"); }
        return event;
    }
    Event movie(long version, String type) { return catalog(content, "MOVIE", null, version, type, BASE.plusSeconds(version)); }
    Event profile(long version, String type) {
        var event = template(InputTopic.PROFILES, "profile-created");
        event.root.put("aggregateVersion", version).put("eventType", type); return event;
    }
    public Event playback(long sequence, long increment, long total) {
        var event = template(InputTopic.PLAYBACK, "playback-started");
        event.root.put("aggregateVersion", sequence).put("occurredAt", BASE.plusSeconds(sequence).toString());
        event.payload().put("sequence", sequence).put("positionMs", total).put("acceptedWatchedMs", increment)
                .put("totalWatchedMs", total).put("qualified", total >= 30000); return event;
    }
    static Path directory() {
        for (Path current = Path.of("").toAbsolutePath(); current != null; current = current.getParent()) {
            Path candidate = current.resolve("contracts/fixtures/analytics-v1");
            if (Files.isRegularFile(candidate.resolve("manifest.json"))) { return candidate; }
        }
        throw new IllegalStateException("Fixture manifest not found");
    }
    static JsonNode read(Path path) {
        try { return JSON.readTree(Files.readString(path)); }
        catch (Exception failure) { throw new IllegalStateException("Fixture read failed"); }
    }
}
