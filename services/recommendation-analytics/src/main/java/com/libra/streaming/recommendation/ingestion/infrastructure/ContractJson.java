package com.libra.streaming.recommendation.ingestion.infrastructure;

import com.libra.streaming.recommendation.ingestion.domain.CatalogChange;
import com.libra.streaming.recommendation.ingestion.domain.EventRejection;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/** Shared strict primitives for Core's event and snapshot contracts. */
public final class ContractJson {
    private ContractJson() {}
    static final Pattern UUID_TEXT = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern DATE_TIME_TEXT = Pattern.compile(
            "\\d{4}-\\d{2}-\\d{2}[Tt]\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,9})?([Zz]|[+-]\\d{2}:\\d{2})");
    private static final Pattern GENRE = Pattern.compile("[a-z0-9-]{1,40}");
    private static final Pattern LANGUAGE = Pattern.compile("[a-z]{2,3}(-[A-Z]{2})?");
    private static final Pattern IMAGE_REFERENCE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_/-]{0,199}");
    private static final Set<String> REVISION = Set.of("revision", "metadata", "createdAt");
    private static final Set<String> METADATA = Set.of("title", "description", "genres", "releaseYear", "language",
            "credits", "imageReferences", "accessTier");
    private static final Set<String> BINDING = Set.of("id", "contentId", "assetId", "assetVersion", "projectionVersion",
            "state", "durationSeconds");
    public static CatalogChange.Revision revision(JsonNode node) {
        if (!node.isObject()) { throw EventRejection.schema("REVISION"); }
        closed(node, REVISION, "REVISION");
        JsonNode metadata = node.get("metadata");
        if (!metadata.isObject()) { throw EventRejection.schema("METADATA"); }
        closed(metadata, METADATA, "METADATA");
        String tier = text(metadata.get("accessTier"), "ACCESS_TIER");
        if (!tier.equals("FREE") && !tier.equals("PREMIUM")) { throw EventRejection.schema("ACCESS_TIER"); }
        String language = text(metadata.get("language"), "LANGUAGE");
        if (!LANGUAGE.matcher(language).matches()) { throw EventRejection.schema("LANGUAGE"); }
        var values = new CatalogChange.Metadata(bounded(metadata.get("title"), 1, 200, "TITLE"),
                bounded(metadata.get("description"), 0, 5000, "DESCRIPTION"),
                strings(metadata.get("genres"), 20, "GENRES", text -> GENRE.matcher(text).matches()),
                (int) integer(metadata.get("releaseYear"), 1888, 2200, "RELEASE_YEAR"), language,
                strings(metadata.get("credits"), 50, "CREDITS", text -> length(text) >= 1 && length(text) <= 120),
                strings(metadata.get("imageReferences"), 10, "IMAGE_REFERENCES",
                        text -> IMAGE_REFERENCE.matcher(text).matches()), tier);
        return new CatalogChange.Revision(integer(node.get("revision"), 1, Long.MAX_VALUE, "REVISION_NUMBER"), values,
                instant(node.get("createdAt"), "REVISION_CREATED_AT"));
    }

    public static CatalogChange.Binding binding(JsonNode node) {
        if (!node.isObject()) { throw EventRejection.schema("BINDING"); }
        closed(node, BINDING, "BINDING");
        String state = text(node.get("state"), "BINDING_STATE");
        if (!Set.of("PENDING", "PROCESSING", "READY", "FAILED").contains(state)) {
            throw EventRejection.schema("BINDING_STATE");
        }
        JsonNode duration = node.get("durationSeconds");
        return new CatalogChange.Binding(uuid(node.get("id"), "BINDING_ID"), uuid(node.get("contentId"), "BINDING_CONTENT"),
                uuid(node.get("assetId"), "BINDING_ASSET"), integer(node.get("assetVersion"), 1, Long.MAX_VALUE, "ASSET_VERSION"),
                integer(node.get("projectionVersion"), 0, Long.MAX_VALUE, "PROJECTION_VERSION"), state,
                duration.isNull() ? null : (int) integer(duration, 1, 604_800, "DURATION"));
    }

    public static void required(JsonNode node, Set<String> fields, String code) {
        for (String field : fields) { if (!node.has(field)) { throw EventRejection.schema(code); } }
    }

    /** Every listed field is present and no other field is: V1 payloads are closed. */
    public static void closed(JsonNode node, Set<String> fields, String code) {
        required(node, fields, code);
        for (String field : node.propertyNames()) { if (!fields.contains(field)) { throw EventRejection.schema(code); } }
    }

    public static String text(JsonNode node, String code) {
        if (node == null || !node.isString()) { throw EventRejection.schema(code); }
        return node.stringValue();
    }

    private static int length(String text) { return text.codePointCount(0, text.length()); }

    private static String bounded(JsonNode node, int min, int max, String code) {
        String text = text(node, code);
        int length = length(text);
        if (length < min || length > max) { throw EventRejection.schema(code); }
        return text;
    }

    private static List<String> strings(JsonNode node, int maxItems, String code,
            java.util.function.Predicate<String> item) {
        if (node == null || !node.isArray() || node.size() > maxItems) { throw EventRejection.schema(code); }
        var values = new ArrayList<String>(node.size());
        for (JsonNode element : node.values()) {
            String text = text(element, code);
            if (!item.test(text)) { throw EventRejection.schema(code); }
            values.add(text);
        }
        return values;
    }

    public static long integer(JsonNode node, long min, long max, String code) {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToLong()) { throw EventRejection.schema(code); }
        long value = node.longValue();
        if (value < min || value > max) { throw EventRejection.schema(code); }
        return value;
    }

    public static UUID uuid(JsonNode node, String code) {
        String text = text(node, code);
        if (!UUID_TEXT.matcher(text).matches()) { throw EventRejection.schema(code); }
        return UUID.fromString(text);
    }

    public static Instant instant(JsonNode node, String code) {
        String text = text(node, code);
        if (!DATE_TIME_TEXT.matcher(text).matches()) { throw EventRejection.schema(code); }
        try {
            var value = OffsetDateTime.parse(text.toUpperCase(Locale.ROOT));
            if (value.getYear() < 1) { throw EventRejection.schema(code); }
            return value.toInstant();
        } catch (java.time.DateTimeException invalid) { throw EventRejection.schema(code); }
    }
}
