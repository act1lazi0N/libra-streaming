package com.libra.streaming.core.integration.analytics;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

/** Edge cases of the Analytics V1 wire forms that the shared fixture corpus does not enumerate. */
class StrictContractJsonTest {
    private static final ObjectMapper MAPPER = JsonMapper.builder().addModule(StrictContractJson.module()).build();
    record Instants(Instant value) {}
    record Dates(LocalDate value) {}
    record Ids(UUID value) {}

    @ParameterizedTest
    @ValueSource(strings = {"\"2026-09-07T10:14:30Z\"", "\"2026-09-07t10:14:30z\"", "\"2026-09-07T17:14:30+07:00\"",
            "\"2026-09-07T10:14:30-00:00\"", "\"2026-09-07T10:14:30.123456789Z\"", "\"2026-09-07T10:14:30+18:00\"",
            "\"0001-01-01T00:00:00Z\""})
    void acceptsRfc3339Instants(String json) {
        assertThat(read(json, Instants.class).value()).isNotNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"1788775000", "1788775000.5", "[2026,9,7,10,14,30]", "{}", "true", "\"\"",
            "\"2026-09-07T10:14Z\"", "\"2026-09-07T10:14:30\"", "\"2026-09-07 10:14:30Z\"", "\"2026-09-07T10:14:30+0700\"",
            "\"2026-09-07T10:14:30.1234567890Z\"", "\"2026-09-07T10:14:30+19:00\"", "\"2026-02-30T10:14:30Z\"",
            "\"2026-09-07T23:59:60Z\"", "\"0000-01-01T00:00:00Z\"", "\"2026-09-07T10:14:30Z\\n\"", "\"+2026-09-07T10:14:30Z\""})
    void rejectsOtherInstantForms(String json) {
        assertThatThrownBy(() -> read(json, Instants.class)).isInstanceOf(RuntimeException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"20697", "[2026,9,1]", "\"2026-9-1\"", "\"20260901\"", "\"2026-09-01T00:00:00Z\"",
            "\"2026-02-30\"", "\"0000-01-01\"", "\"\"", "{}"})
    void rejectsOtherDateForms(String json) {
        assertThatThrownBy(() -> read(json, Dates.class)).isInstanceOf(RuntimeException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa\"", "\"AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA\""})
    void acceptsDashedUuidsInEitherCase(String json) {
        assertThat(read(json, Ids.class).value()).isEqualTo(UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"qqqqqqqqSqqKqqqqqqqqqg==\"", "\"aaaaaaaaaaaa4aaa8aaaaaaaaaaaaaaa\"", "\"{aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa}\"",
            "\"urn:uuid:aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa\"", "\"a-a-a-a-a\"", "\"\"", "123", "[]"})
    void rejectsOtherUuidForms(String json) {
        assertThatThrownBy(() -> read(json, Ids.class)).isInstanceOf(RuntimeException.class);
    }

    private static <T> T read(String json, Class<T> type) {
        return MAPPER.readValue("{\"value\":" + json + "}", type);
    }
}
