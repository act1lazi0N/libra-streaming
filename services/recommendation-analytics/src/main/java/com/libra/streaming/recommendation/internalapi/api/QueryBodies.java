package com.libra.streaming.recommendation.internalapi.api;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.springframework.http.MediaType;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Strict reading of a V1 query body: UTF-8 {@code application/json}, bounded size, a closed object with exactly the
 * expected fields, no duplicate keys and no trailing content. Failures carry no request text.
 */
final class QueryBodies {
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    static final class Rejected extends RuntimeException {
        final Problems.Code code;

        Rejected(Problems.Code code) {
            super(code.name(), null, false, false);
            this.code = code;
        }
    }

    private QueryBodies() {}

    /** The body as an object holding exactly {@code fields}. */
    static JsonNode readObject(String contentType, long contentLength, InputStream body, int maxBytes,
            Set<String> fields) throws IOException {
        requireJson(contentType);
        if (contentLength > maxBytes) { throw new Rejected(Problems.Code.PAYLOAD_TOO_LARGE); }
        byte[] bytes = body.readNBytes(maxBytes + 1);
        if (bytes.length > maxBytes) { throw new Rejected(Problems.Code.PAYLOAD_TOO_LARGE); }
        JsonNode root;
        try { root = MAPPER.readTree(utf8(bytes)); }
        catch (JacksonException | CharacterCodingException malformed) { throw invalid(); }
        if (root == null || !root.isObject() || root.size() != fields.size()) { throw invalid(); }
        for (String field : root.propertyNames()) { if (!fields.contains(field)) { throw invalid(); } }
        return root;
    }

    /** Malformed UTF-8 is rejected, not replaced by U+FFFD. */
    private static String utf8(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }

    static Rejected invalid() { return new Rejected(Problems.Code.INVALID_REQUEST); }

    private static void requireJson(String contentType) {
        if (contentType == null) { throw new Rejected(Problems.Code.UNSUPPORTED_MEDIA_TYPE); }
        MediaType type;
        boolean utf8;
        try {
            type = MediaType.parseMediaType(contentType);
            utf8 = type.getCharset() == null || StandardCharsets.UTF_8.equals(type.getCharset());
        } catch (IllegalArgumentException malformed) {
            // InvalidMediaTypeException, and the unknown or illegal charset name exceptions of getCharset().
            throw new Rejected(Problems.Code.UNSUPPORTED_MEDIA_TYPE);
        }
        if (!MediaType.APPLICATION_JSON.equalsTypeAndSubtype(type) || !utf8) {
            throw new Rejected(Problems.Code.UNSUPPORTED_MEDIA_TYPE);
        }
    }
}
