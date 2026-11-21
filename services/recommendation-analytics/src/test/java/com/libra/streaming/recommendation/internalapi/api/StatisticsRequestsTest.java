package com.libra.streaming.recommendation.internalapi.api;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StatisticsRequestsTest {
    private static final String VALID = "{\"from\":\"2026-09-01\",\"to\":\"2026-09-07\"}";

    @ParameterizedTest
    @ValueSource(strings = {"application/json", "application/json; charset=utf-8", "Application/JSON;charset=UTF-8"})
    void acceptsUtf8JsonMediaTypes(String mediaType) throws Exception {
        byte[] body = VALID.getBytes(StandardCharsets.UTF_8);
        assertThat(StatisticsRequests.read(mediaType, body.length, new ByteArrayInputStream(body)))
                .isEqualTo(new StatisticsRequests.Query(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 7)));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"text/plain", "application/json-patch+json", "application/json; charset=iso-8859-1",
            "application/json; charset=nope-unknown", "application/json; charset=@@", "not a media type"})
    void rejectsUnsupportedOrMalformedMediaTypes(String mediaType) {
        byte[] body = VALID.getBytes(StandardCharsets.UTF_8);
        rejects(mediaType, body.length, new ByteArrayInputStream(body), Problems.Code.UNSUPPORTED_MEDIA_TYPE);
    }

    @Test
    void oversizedDeclaredLengthIsRejectedBeforeReadingStream() {
        InputStream unreadable = new InputStream() {
            @Override
            public int read() { throw new AssertionError("Oversized request stream was read"); }
        };
        rejects("application/json", 1025, unreadable, Problems.Code.PAYLOAD_TOO_LARGE);
    }

    @Test
    void unknownLengthStillEnforcesBodyLimit() {
        rejects("application/json", -1, new ByteArrayInputStream(new byte[1025]), Problems.Code.PAYLOAD_TOO_LARGE);
    }

    @Test
    void exactly1024BytesOfValidJsonWithSpacePaddingAreAccepted() throws Exception {
        byte[] body = (VALID + " ".repeat(1024 - VALID.length())).getBytes(StandardCharsets.UTF_8);
        assertThat(body).hasSize(1024);
        assertThat(StatisticsRequests.read("application/json", body.length, new ByteArrayInputStream(body)))
                .isEqualTo(new StatisticsRequests.Query(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 7)));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "", "null", "[]", "\"x\"", "{}",
            "{\"from\":\"2026-09-01\"}",
            "{\"from\":\"2026-09-01\",\"to\":\"2026-09-07\",\"extra\":true}",
            "{\"from\":\"2026-09-01\",\"from\":\"2026-09-02\",\"to\":\"2026-09-07\"}",
            "{\"from\":20260907,\"to\":\"2026-09-07\"}",
            "{\"from\":[2026,9,7],\"to\":\"2026-09-07\"}",
            "{\"from\":\"2026-09-07T00:00:00Z\",\"to\":\"2026-09-07\"}",
            "{\"from\":\"2026-02-30\",\"to\":\"2026-09-07\"}",
            "{\"from\":\"+2026-09-07\",\"to\":\"2026-09-07\"}",
            "{\"from\":\" 2026-09-07\",\"to\":\"2026-09-07\"}",
            "{\"from\":\"2026-9-7\",\"to\":\"2026-09-07\"}",
            "{\"from\":\"2026-09-01\",\"to\":\"2026-09-07\"} {}",
            "{\"from\":null,\"to\":\"2026-09-07\"}",
            "{\"from\":\"2026-09-01\",\"to\":null}"
    })
    void rejectsInvalidJsonShapeOrDates(String json) {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        rejects("application/json", body.length, new ByteArrayInputStream(body), Problems.Code.INVALID_REQUEST);
    }

    @Test
    void rejectsInvalidUtf8Bytes() {
        byte[] body = VALID.getBytes(StandardCharsets.UTF_8);
        body[VALID.indexOf("2026")] = (byte) 0xff;
        rejects("application/json", body.length, new ByteArrayInputStream(body), Problems.Code.INVALID_REQUEST);
    }

    private static void rejects(String mediaType, long length, InputStream body, Problems.Code code) {
        assertThatThrownBy(() -> StatisticsRequests.read(mediaType, length, body))
                .isInstanceOfSatisfying(QueryBodies.Rejected.class, rejected -> {
                    assertThat(rejected.code).isEqualTo(code);
                    assertThat(rejected.getMessage()).isEqualTo(code.name());
                });
    }
}
