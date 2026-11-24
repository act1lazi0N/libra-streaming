package com.libra.streaming.recommendation.internalapi.api;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

/** Strict parser checks with in-memory streams; no PostgreSQL. */
class RecommendationListRequestsTest {
    private static final String ACCOUNT = "abcdef01-2345-6789-abcd-ef0123456789";
    private static final String PROFILE = "12345678-1234-4234-8234-123456789abc";
    private static final String VALID = body("\"" + ACCOUNT + "\"", "20");

    @Test
    void validBodyAndUppercaseUuidsParse() throws Exception {
        for (String json : new String[] {VALID, VALID.toUpperCase(java.util.Locale.ROOT)
                .replace("ACCOUNTID", "accountId").replace("PROFILEID", "profileId").replace("LIMIT", "limit")
                .replace("LIST", "list").replace("SEEDCONTENTID", "seedContentId").replace("NULL", "null")}) {
            assertThat(read(json)).isEqualTo(new RecommendationListRequests.Query(
                    UUID.fromString(ACCOUNT), UUID.fromString(PROFILE),
                    com.libra.streaming.recommendation.recommendations.domain.ListKind.PERSONAL, null, 20));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "[]", "null", "true", "20", "\"x\"",
            "{\"accountId\":\"abcdef01-2345-6789-abcd-ef0123456789\"}"})
    void rejectsMissingFieldsAndNonObjects(String json) {
        invalid(json);
    }

    @Test
    void rejectsExtraFieldsDuplicateKeysAndTrailingTokens() {
        invalid(VALID.replace("}", ",\"extra\":1}"));
        invalid(VALID.replace("\"limit\":20", "\"limit\":20,\"limit\":21"));
        invalid(VALID + " {}");
        invalid(VALID.replace("\"profileId\"", "\"other\""));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "101", "\"20\"", "20.0", "2e1", "3000000000", "null", "true"})
    void rejectsInvalidLimitsWithoutCoercion(String limit) {
        invalid(body("\"" + ACCOUNT + "\"", limit));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"abcdef0123456789abcdef0123456789\"",
            "\"{abcdef01-2345-6789-abcd-ef0123456789}\"",
            "\"abcdef01-2345-6789-abcd-ef012345678\"",
            "\"gbcdef01-2345-6789-abcd-ef0123456789\"", "1234"})
    void rejectsNonCanonicalUuidsInEitherField(String uuid) {
        invalid(body(uuid, "20"));
        invalid(VALID.replace("\"" + PROFILE + "\"", uuid));
    }

    @Test
    void oversizedDeclaredLengthIsRejectedBeforeReading() {
        InputStream unreadable = new InputStream() {
            @Override public int read() { throw new AssertionError("Oversized request stream was read"); }
        };
        rejects("application/json", 1025, unreadable, Problems.Code.PAYLOAD_TOO_LARGE);
    }

    @Test
    void actualLengthIsBoundedEvenWhenUnknownOrUnderdeclared() {
        for (long length : new long[] {-1, 1}) {
            rejects("application/json", length, new ByteArrayInputStream(new byte[1025]),
                    Problems.Code.PAYLOAD_TOO_LARGE);
        }
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"text/plain", "application/json; charset=iso-8859-1"})
    void rejectsUnsupportedMediaTypes(String type) {
        rejects(type, VALID.length(), stream(VALID), Problems.Code.UNSUPPORTED_MEDIA_TYPE);
    }

    @Test
    void rejectsMalformedUtf8InsideStringValuesBeforeFieldValidation() {
        byte[][] bodies = {
                {'{', '"', 'v', '"', ':', '"', (byte) 0xc3, 0x28, '"', '}'},
                {'{', '"', 'v', '"', ':', '"', (byte) 0xff, '"', '}'}
        };
        for (byte[] body : bodies) {
            // An unrestricted string would accept replacement characters if decoding were lenient.
            assertThatThrownBy(() -> QueryBodies.readObject("application/json", body.length,
                    new ByteArrayInputStream(body), 1024, Set.of("v")))
                    .isInstanceOfSatisfying(QueryBodies.Rejected.class, rejected -> {
                        assertThat(rejected.code).isEqualTo(Problems.Code.INVALID_REQUEST);
                        assertThat(new Problems(new ObjectMapper())
                                .entity(rejected.code, new MockHttpServletRequest())
                                .getStatusCode().value()).isEqualTo(400);
                    });
        }
    }


    @Test
    void everyExactListNameAndBoundaryLimitParses() throws Exception {
        for (var list : com.libra.streaming.recommendation.recommendations.domain.ListKind.values()) {
            for (int limit : new int[] {1, 100}) {
                String json = body("\"" + ACCOUNT + "\"", Integer.toString(limit))
                        .replace("PERSONAL", list.name());
                if (list.name().equals("SIMILAR")) { json = json.replace("null", "\"" + PROFILE + "\""); }
                var query = read(json);
                assertThat(query.list()).isEqualTo(list);
                assertThat(query.seed()).isEqualTo(list.name().equals("SIMILAR") ? UUID.fromString(PROFILE) : null);
                assertThat(query.limit()).isEqualTo(limit);
            }
        }
    }

    @Test
    void rejectsWrongCaseMissingFieldsAndEverySeedMismatch() {
        for (String list : new String[] {"personal", "similar", "popular", "new", "UNKNOWN", ""}) {
            invalid(VALID.replace("PERSONAL", list));
        }
        invalid(VALID.replace("PERSONAL", "SIMILAR"));
        for (String list : new String[] {"PERSONAL", "POPULAR", "NEW"}) {
            invalid(VALID.replace("PERSONAL", list).replace("null", "\"" + PROFILE + "\""));
        }
        for (String field : new String[] {"accountId", "profileId", "list", "seedContentId", "limit"}) {
            var root = (tools.jackson.databind.node.ObjectNode) new ObjectMapper().readTree(VALID);
            root.remove(field);
            invalid(root.toString());
        }
        for (String seed : new String[] {"123", "true", "\"1-1-1-1-1\"", "\"not-a-uuid\"",
                "\"12345678123442348234123456789abc\""}) {
            invalid(VALID.replace("PERSONAL", "SIMILAR").replace("null", seed));
        }
    }

    private static String body(String account, String limit) {
        return "{\"accountId\":" + account + ",\"profileId\":\"" + PROFILE + "\",\"limit\":" + limit + ",\"list\":\"PERSONAL\",\"seedContentId\":null}";
    }

    private static RecommendationListRequests.Query read(String json) throws Exception {
        return RecommendationListRequests.read("application/json", -1, stream(json));
    }

    private static ByteArrayInputStream stream(String json) {
        return new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
    }

    private static void invalid(String json) {
        rejects("application/json", -1, stream(json), Problems.Code.INVALID_REQUEST);
    }

    private static void rejects(String type, long length, InputStream stream, Problems.Code code) {
        assertThatThrownBy(() -> RecommendationListRequests.read(type, length, stream))
                .isInstanceOfSatisfying(QueryBodies.Rejected.class, rejected -> assertThat(rejected.code).isEqualTo(code));
    }
}
