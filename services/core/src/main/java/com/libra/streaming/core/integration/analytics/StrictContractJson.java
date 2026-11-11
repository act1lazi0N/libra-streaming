package com.libra.streaming.core.integration.analytics;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.module.SimpleModule;

/**
 * Wire-exact readers for the Analytics V1 contract. Jackson otherwise also accepts epoch numbers for instants,
 * [year, month, day] arrays for dates and 24-character Base64 for UUIDs; none of these is contract JSON, and a
 * downstream that emits them must degrade exactly like any other invalid response.
 */
final class StrictContractJson {
    private static final Pattern UUID_TEXT = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern DATE_TEXT = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    // RFC 3339 date-time: seconds and an explicit offset are mandatory. Core's profile is narrower than JSON Schema's
    // in fail-safe ways only: at most 9 fraction digits, offsets within +-18:00, no leap second, no trailing newline,
    // years from 0001.
    private static final Pattern DATE_TIME_TEXT = Pattern.compile(
            "\\d{4}-\\d{2}-\\d{2}[Tt]\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,9})?([Zz]|[+-]\\d{2}:\\d{2})");

    private StrictContractJson() {}

    static SimpleModule module() {
        return new SimpleModule("analytics-contract-v1")
                .addDeserializer(UUID.class, new Reader<>(UUID.class, UUID_TEXT) {
                    @Override UUID parse(String text) { return UUID.fromString(text); }
                })
                .addDeserializer(LocalDate.class, new Reader<>(LocalDate.class, DATE_TEXT) {
                    @Override LocalDate parse(String text) {
                        var value = LocalDate.parse(text);
                        return yearOne(value.getYear(), value);
                    }
                })
                .addDeserializer(Instant.class, new Reader<>(Instant.class, DATE_TIME_TEXT) {
                    @Override Instant parse(String text) {
                        var value = OffsetDateTime.parse(text.toUpperCase(Locale.ROOT));
                        return yearOne(value.getYear(), value.toInstant());
                    }
                });
    }

    /** RFC 3339 and JSON Schema have no year 0000; java.time would read it as 1 BC. */
    private static <T> T yearOne(int year, T value) {
        if (year < 1) { throw new IllegalArgumentException("Year before 0001"); }
        return value;
    }

    private abstract static class Reader<T> extends ValueDeserializer<T> {
        private final Class<T> type;
        private final Pattern pattern;
        Reader(Class<T> type, Pattern pattern) { this.type = type; this.pattern = pattern; }
        abstract T parse(String text);
        @Override public T deserialize(JsonParser parser, DeserializationContext context) {
            if (!parser.hasToken(JsonToken.VALUE_STRING) || !pattern.matcher(parser.getString()).matches()) {
                return context.reportInputMismatch(type, "Not %s text in the Analytics contract form", type.getSimpleName());
            }
            try {
                return parse(parser.getString());
            } catch (RuntimeException invalid) {
                return context.reportInputMismatch(type, "Invalid %s value", type.getSimpleName());
            }
        }
    }
}
