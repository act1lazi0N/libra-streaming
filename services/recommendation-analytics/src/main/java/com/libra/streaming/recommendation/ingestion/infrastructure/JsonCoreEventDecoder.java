package com.libra.streaming.recommendation.ingestion.infrastructure;

import static com.libra.streaming.recommendation.ingestion.infrastructure.ContractJson.*;

import com.libra.streaming.recommendation.freshness.domain.InputTopic;
import com.libra.streaming.recommendation.ingestion.application.CoreEventDecoder;
import com.libra.streaming.recommendation.ingestion.domain.CatalogChange;
import com.libra.streaming.recommendation.ingestion.domain.CoreEvent;
import com.libra.streaming.recommendation.ingestion.domain.Envelope;
import com.libra.streaming.recommendation.ingestion.domain.EventRejection;
import com.libra.streaming.recommendation.ingestion.domain.PlaybackProgress;
import com.libra.streaming.recommendation.ingestion.domain.ProfileChange;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * A hand-written mirror of the V1 event schemas (contracts/events): the envelope is open, every payload is closed,
 * and every format and bound is checked before a value is used. The shared fixture corpus pins it to the schemas.
 * Where this decoder is narrower than JSON Schema it fails safe, as Core's StrictContractJson does: integers must be
 * written as integers, date-times need seconds and an explicit offset, and years start at 0001.
 */
public final class JsonCoreEventDecoder implements CoreEventDecoder {
    /** Core's largest event is a catalog publication with full metadata, far below this bound. */
    public static final int MAX_RECORD_BYTES = 262_144;
    private static final Pattern EVENT_TYPE = Pattern.compile("[A-Za-z][A-Za-z0-9.]{0,99}");
    private static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;
    private static final Set<String> ENVELOPE = Set.of("eventId", "eventType", "schemaVersion", "aggregateId",
            "aggregateVersion", "occurredAt", "correlationId", "payload");
    private static final Set<String> CATALOG = Set.of("contentId", "kind", "parentId", "ordinal", "published", "active");
    private static final Set<String> PROFILE = Set.of("accountId", "profileId");
    private static final Set<String> PLAYBACK = Set.of("accountId", "profileId", "sessionId", "contentId", "assetId",
            "assetVersion", "sequence", "positionMs", "acceptedWatchedMs", "totalWatchedMs", "qualified", "state");

    private final ObjectMapper mapper = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();

    @Override
    public CoreEvent decode(InputTopic topic, String key, String value) {
        if (value == null || value.isEmpty()) { throw EventRejection.schema("EMPTY_RECORD"); }
        if (value.length() > MAX_RECORD_BYTES || value.getBytes(StandardCharsets.UTF_8).length > MAX_RECORD_BYTES) {
            throw EventRejection.schema("RECORD_TOO_LARGE");
        }
        JsonNode root;
        try { root = mapper.readTree(value); }
        catch (JacksonException malformed) { throw EventRejection.schema("MALFORMED_JSON"); }
        if (root == null || !root.isObject()) { throw EventRejection.schema("ENVELOPE_NOT_OBJECT"); }
        required(root, ENVELOPE, "ENVELOPE_FIELD");
        JsonNode version = root.get("schemaVersion");
        if (!version.isIntegralNumber() || !version.canConvertToLong() || version.longValue() != 1) {
            throw EventRejection.schema("SCHEMA_VERSION");
        }
        String eventType = text(root.get("eventType"), "EVENT_TYPE");
        if (!EVENT_TYPE.matcher(eventType).matches()) { throw EventRejection.schema("EVENT_TYPE"); }
        var envelope = new Envelope(uuid(root.get("eventId"), "EVENT_ID"), eventType,
                uuid(root.get("aggregateId"), "AGGREGATE_ID"),
                integer(root.get("aggregateVersion"), 1, Long.MAX_VALUE, "AGGREGATE_VERSION"),
                instant(root.get("occurredAt"), "OCCURRED_AT"), uuid(root.get("correlationId"), "CORRELATION_ID"));
        JsonNode payload = root.get("payload");
        if (!payload.isObject()) { throw EventRejection.schema("PAYLOAD_NOT_OBJECT"); }
        CoreEvent event = switch (topic) {
            case CATALOG -> catalog(envelope, payload);
            case PROFILES -> profile(envelope, payload);
            case PLAYBACK -> playback(envelope, payload);
        };
        // The Kafka key is the aggregate ID Core partitioned by; a mismatch would route facts to the wrong order.
        if (key == null || !ContractJson.UUID_TEXT.matcher(key).matches() || !UUID.fromString(key).equals(envelope.aggregateId())) {
            throw EventRejection.semantic("KEY_NOT_AGGREGATE");
        }
        return event;
    }

    private static CatalogChange catalog(Envelope envelope, JsonNode payload) {
        CatalogChange.Type type = CatalogChange.Type.fromWireName(envelope.eventType());
        closed(payload, CATALOG, "CATALOG_PAYLOAD");
        CatalogChange.Kind kind;
        try { kind = CatalogChange.Kind.valueOf(text(payload.get("kind"), "KIND")); }
        catch (IllegalArgumentException unknown) { throw EventRejection.schema("KIND"); }
        UUID parentId = payload.get("parentId").isNull() ? null : uuid(payload.get("parentId"), "PARENT_ID");
        Integer ordinal = payload.get("ordinal").isNull() ? null
                : (int) integer(payload.get("ordinal"), 1, Integer.MAX_VALUE, "ORDINAL");
        CatalogChange.Revision published = payload.get("published").isNull() ? null : revision(payload.get("published"));
        CatalogChange.Binding active = payload.get("active").isNull() ? null : binding(payload.get("active"));
        return new CatalogChange(envelope, type, uuid(payload.get("contentId"), "CONTENT_ID"), kind, parentId, ordinal,
                published, active);
    }

    private static ProfileChange profile(Envelope envelope, JsonNode payload) {
        ProfileChange.Type type = ProfileChange.Type.fromWireName(envelope.eventType());
        closed(payload, PROFILE, "PROFILE_PAYLOAD");
        return new ProfileChange(envelope, type, uuid(payload.get("accountId"), "ACCOUNT_ID"),
                uuid(payload.get("profileId"), "PROFILE_ID"));
    }

    private static PlaybackProgress playback(Envelope envelope, JsonNode payload) {
        if (!envelope.eventType().equals("PlaybackProgressAccepted")) { throw EventRejection.schema("EVENT_TYPE"); }
        closed(payload, PLAYBACK, "PLAYBACK_PAYLOAD");
        PlaybackProgress.State state;
        try { state = PlaybackProgress.State.valueOf(text(payload.get("state"), "STATE")); }
        catch (IllegalArgumentException unknown) { throw EventRejection.schema("STATE"); }
        JsonNode qualified = payload.get("qualified");
        if (!qualified.isBoolean()) { throw EventRejection.schema("QUALIFIED"); }
        return new PlaybackProgress(envelope, uuid(payload.get("accountId"), "ACCOUNT_ID"),
                uuid(payload.get("profileId"), "PROFILE_ID"), uuid(payload.get("sessionId"), "SESSION_ID"),
                uuid(payload.get("contentId"), "CONTENT_ID"), uuid(payload.get("assetId"), "ASSET_ID"),
                integer(payload.get("assetVersion"), 1, Long.MAX_VALUE, "ASSET_VERSION"),
                integer(payload.get("sequence"), 1, MAX_SAFE_INTEGER, "SEQUENCE"),
                integer(payload.get("positionMs"), 0, 604_800_000L, "POSITION"),
                integer(payload.get("acceptedWatchedMs"), 0, 30_000L, "ACCEPTED_WATCHED"),
                integer(payload.get("totalWatchedMs"), 0, Long.MAX_VALUE, "TOTAL_WATCHED"),
                qualified.booleanValue(), state);
    }

}
