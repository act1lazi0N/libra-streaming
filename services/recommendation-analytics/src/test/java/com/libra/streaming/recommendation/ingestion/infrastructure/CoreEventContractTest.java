package com.libra.streaming.recommendation.ingestion.infrastructure;

import com.libra.streaming.recommendation.freshness.domain.InputTopic;
import com.libra.streaming.recommendation.ingestion.domain.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CoreEventContractTest {
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final Path DIRECTORY = directory();
    static final JsonNode MANIFEST = read(DIRECTORY.resolve("manifest.json"));
    static final JsonCoreEventDecoder DECODER = new JsonCoreEventDecoder();

    static Path directory() {
        for (Path current = Path.of("").toAbsolutePath(); current != null; current = current.getParent()) {
            Path candidate = current.resolve("contracts/fixtures/analytics-v1");
            if (Files.isRegularFile(candidate.resolve("manifest.json"))) { return candidate; }
        }
        throw new IllegalStateException("Fixture manifest not found");
    }

    static JsonNode read(Path path) {
        try { return JSON.readTree(Files.readString(path)); }
        catch (Exception failure) { throw new IllegalStateException(failure); }
    }

    static String template(String file) {
        try {
            String text = Files.readString(DIRECTORY.resolve("events/" + file + ".json"));
            var matcher = Pattern.compile("\\{\\{([A-Za-z][A-Za-z0-9]*)}}").matcher(text);
            var result = new StringBuilder();
            while (matcher.find()) {
                JsonNode sample = MANIFEST.path("placeholders").get(matcher.group(1));
                assertThat(sample).isNotNull();
                matcher.appendReplacement(result, java.util.regex.Matcher.quoteReplacement(sample.stringValue()));
            }
            return matcher.appendTail(result).toString();
        } catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
    }

    static String key(InputTopic topic) {
        return MANIFEST.path("placeholders").path(switch (topic) {
            case PLAYBACK -> "sessionId"; case CATALOG -> "contentId"; case PROFILES -> "profileId";
        }).stringValue();
    }

    static EventRejection rejected(InputTopic topic, String key, String body, EventRejection.Reason reason) {
        var rejection = assertThrows(EventRejection.class, () -> DECODER.decode(topic, key, body));
        assertThat(rejection.reason()).isEqualTo(reason);
        assertThat(rejection.getMessage()).doesNotContain("PLAYING", "payload", "title", "private-marker");
        assertThat(Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
                .matcher(rejection.getMessage()).find()).isFalse();
        return rejection;
    }

    @TestFactory
    List<DynamicTest> corpus() {
        var tests = new ArrayList<DynamicTest>();
        var counts = new EnumMap<InputTopic, Integer>(InputTopic.class);
        for (JsonNode entry : MANIFEST.path("fixtures")) {
            String file = entry.path("file").stringValue();
            if (!file.startsWith("events/")) { continue; }
            InputTopic topic = InputTopic.fromWireName(entry.path("topic").stringValue());
            counts.merge(topic, 1, Integer::sum);
            tests.add(DynamicTest.dynamicTest(entry.path("id").stringValue(), () -> {
                String body = template(file.substring(7, file.length() - 5));
                String expect = entry.path("expect").stringValue();
                if (expect.equals("valid")) { assertThat(DECODER.decode(topic, key(topic), body)).isNotNull(); }
                else {
                    assertThat(expect).isIn("schema-invalid", "semantic-invalid");
                    rejected(topic, key(topic), body, expect.equals("schema-invalid")
                            ? EventRejection.Reason.SCHEMA_INVALID : EventRejection.Reason.SEMANTIC_INVALID);
                }
            }));
        }
        assertThat(tests).hasSize(32);
        assertThat(counts).containsExactlyInAnyOrderEntriesOf(Map.of(InputTopic.PLAYBACK, 14,
                InputTopic.CATALOG, 11, InputTopic.PROFILES, 7));
        return tests;
    }

    static ObjectNode playback() { return (ObjectNode) JSON.readTree(template("playback-started")); }
    static ObjectNode payload(ObjectNode root) { return (ObjectNode) root.get("payload"); }
    static void schema(String body) {
        rejected(InputTopic.PLAYBACK, key(InputTopic.PLAYBACK), body, EventRejection.Reason.SCHEMA_INVALID);
    }

    @Test
    void decodedFieldsMatchAllThreeTemplates() {
        var playback = (PlaybackProgress) DECODER.decode(InputTopic.PLAYBACK, key(InputTopic.PLAYBACK), template("playback-started"));
        assertThat(playback.sessionId()).isEqualTo(UUID.fromString(key(InputTopic.PLAYBACK)));
        assertThat(playback.sequence()).isEqualTo(1);
        assertThat(playback.acceptedWatchedMs()).isZero();
        assertThat(playback.state()).isEqualTo(PlaybackProgress.State.PLAYING);
        var catalog = (CatalogChange) DECODER.decode(InputTopic.CATALOG, key(InputTopic.CATALOG), template("catalog-movie-published"));
        assertThat(catalog.contentId()).isEqualTo(UUID.fromString(key(InputTopic.CATALOG)));
        assertThat(catalog.kind()).isEqualTo(CatalogChange.Kind.MOVIE);
        JsonNode expected = JSON.readTree(template("catalog-movie-published")).path("payload").path("published").path("metadata");
        assertThat(catalog.published().metadata().title()).isEqualTo(expected.path("title").stringValue());
        var profile = (ProfileChange) DECODER.decode(InputTopic.PROFILES, key(InputTopic.PROFILES), template("profile-created"));
        assertThat(profile.profileId()).isEqualTo(UUID.fromString(key(InputTopic.PROFILES)));
        assertThat(profile.accountId()).isEqualTo(UUID.fromString(MANIFEST.path("placeholders").path("accountId").stringValue()));
        assertThat(profile.type()).isEqualTo(ProfileChange.Type.CREATED);
        assertThat(profile.envelope().occurredAt()).isEqualTo(Instant.parse(MANIFEST.path("placeholders").path("occurredAt").stringValue()));
    }

    @Test
    void duplicateJsonKey() { schema(template("playback-started").replaceFirst("\\{", "{\\\"schemaVersion\\\":1,")); }

    @Test
    void oversizedUtf8() {
        var root = playback();
        root.put("extra", "😀".repeat(65537));
        var rejection = rejected(InputTopic.PLAYBACK, key(InputTopic.PLAYBACK), root.toString(),
                EventRejection.Reason.SCHEMA_INVALID);
        assertThat(rejection.code()).isEqualTo("RECORD_TOO_LARGE");
    }

    @Test
    void nullValue() { schema(null); }

    @Test
    void emptyValue() { schema(""); }

    @Test
    void mismatchedKey() { rejected(InputTopic.PLAYBACK, key(InputTopic.CATALOG), template("playback-started"), EventRejection.Reason.SEMANTIC_INVALID); }

    @Test
    void nonUuidKey() { rejected(InputTopic.PLAYBACK, "private-marker", template("playback-started"), EventRejection.Reason.SEMANTIC_INVALID); }

    @Test
    void uppercaseUuid() { assertThat(DECODER.decode(InputTopic.PROFILES, key(InputTopic.PROFILES).toUpperCase(Locale.ROOT), template("profile-created").replace(key(InputTopic.PROFILES), key(InputTopic.PROFILES).toUpperCase(Locale.ROOT)))).isNotNull(); }

    @Test
    void fractionalInteger() { var root = playback(); root.put("aggregateVersion", 1.0); schema(root.toString()); }

    @Test
    void lowercaseDate() { var root = playback(); root.put("occurredAt", "2026-09-07t10:15:30z"); assertThat(DECODER.decode(InputTopic.PLAYBACK, key(InputTopic.PLAYBACK), root.toString())).isNotNull(); }

    @Test
    void dateWithoutSeconds() { var root = playback(); root.put("occurredAt", "2026-09-07T10:15Z"); schema(root.toString()); }

    @Test
    void dateWithoutOffset() { var root = playback(); root.put("occurredAt", "2026-09-07T10:15:30"); schema(root.toString()); }

    @Test
    void yearZero() { var root = playback(); root.put("occurredAt", "0000-09-07T10:15:30Z"); schema(root.toString()); }

    @Test
    void extraEnvelope() { var root = playback(); root.put("extra", "private-marker"); assertThat(DECODER.decode(InputTopic.PLAYBACK, key(InputTopic.PLAYBACK), root.toString())).isNotNull(); }

    @Test
    void extraPayload() { var root = playback(); payload(root).put("extra", "private-marker"); schema(root.toString()); }

    @Test
    void schemaVersionTwo() { var root = playback(); root.put("schemaVersion", 2); schema(root.toString()); }

    @Test
    void wrongTopicType() { var root = playback(); root.put("eventType", "ProfileCreated"); schema(root.toString()); }

    @Test
    void title201CodePoints() { title(201, false); }

    @Test
    void title200CodePoints() { title(200, true); }

    static void title(int length, boolean valid) {
        var root = (ObjectNode) JSON.readTree(template("catalog-movie-published"));
        ((ObjectNode) root.path("payload").path("published").path("metadata")).put("title", "😀".repeat(length));
        if (valid) { assertThat(DECODER.decode(InputTopic.CATALOG, key(InputTopic.CATALOG), root.toString())).isNotNull(); }
        else { rejected(InputTopic.CATALOG, key(InputTopic.CATALOG), root.toString(), EventRejection.Reason.SCHEMA_INVALID); }
    }
}

