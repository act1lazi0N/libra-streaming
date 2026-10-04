package com.libra.streaming.media.processing.infrastructure;

import com.libra.streaming.media.processing.application.MediaProber;
import com.libra.streaming.media.processing.application.ProbeReport;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads the {@code -print_format json} output of ffprobe into a {@link ProbeReport}. It copies only the fields
 * the policy needs and normalizes the tool's quirks (numbers sometimes quoted, "N/A" placeholders, the rotation
 * living in either side data or a tag). Anything it cannot read as a document is {@link MediaProber.Unreadable}.
 */
final class FfprobeJson {
    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    private static final int MAX_TRACKS = 64;
    private static final int MAX_NUMBER_TEXT = 32;

    private FfprobeJson() {}

    static ProbeReport parse(byte[] output) {
        JsonNode root;
        try {
            root = MAPPER.readTree(output);
        } catch (JacksonException exception) {
            throw new MediaProber.Unreadable();
        }
        if (root == null || !root.isObject()) { throw new MediaProber.Unreadable(); }
        var format = root.path("format");
        var streams = root.path("streams");
        if (!format.isObject() || !streams.isArray()) { throw new MediaProber.Unreadable(); }
        var tracks = new ArrayList<ProbeReport.Track>();
        for (JsonNode stream : streams) {
            // More tracks than any accepted source could have: stop, since the policy rejects the file anyway.
            if (tracks.size() >= MAX_TRACKS) { break; }
            tracks.add(track(stream));
        }
        return new ProbeReport(new ProbeReport.Format(text(format.path("format_name")),
                text(format.path("tags").path("major_brand")), decimal(format.path("duration"))), List.copyOf(tracks));
    }

    private static ProbeReport.Track track(JsonNode stream) {
        return new ProbeReport.Track(text(stream.path("codec_type")), text(stream.path("codec_name")),
                text(stream.path("profile")), integer(stream.path("width")), integer(stream.path("height")),
                text(stream.path("pix_fmt")), text(stream.path("sample_aspect_ratio")),
                text(stream.path("avg_frame_rate")), text(stream.path("color_transfer")),
                text(stream.path("color_primaries")), rotation(stream),
                Integer.valueOf(1).equals(integer(stream.path("disposition").path("attached_pic"))),
                integer(stream.path("channels")), integer(stream.path("sample_rate")));
    }

    /** Side-data display matrix first (current ffprobe), then the legacy {@code rotate} tag. */
    private static Double rotation(JsonNode stream) {
        for (JsonNode data : stream.path("side_data_list")) {
            var value = decimal(data.path("rotation"));
            if (value != null) { return value.doubleValue(); }
        }
        var legacy = decimal(stream.path("tags").path("rotate"));
        return legacy == null ? null : legacy.doubleValue();
    }

    private static String text(JsonNode node) {
        return node.isString() && node.asString().length() <= 64 ? node.asString() : null;
    }

    /** Whole numbers, whether ffprobe quoted them or not; fractions, placeholders and overflow are null. */
    private static Integer integer(JsonNode node) {
        var value = decimal(node);
        if (value == null || value.stripTrailingZeros().scale() > 0) { return null; }
        try { return value.intValueExact(); }
        catch (ArithmeticException exception) { return null; }
    }

    /** Finite decimal numbers only; "N/A", NaN, infinity, exponents beyond reason and long text become null. */
    private static BigDecimal decimal(JsonNode node) {
        String text;
        if (node.isNumber()) { text = node.asString(); }
        else if (node.isString()) { text = node.asString(); }
        else { return null; }
        if (text.isEmpty() || text.length() > MAX_NUMBER_TEXT) { return null; }
        try {
            var value = new BigDecimal(text);
            // A short string can still spell an absurd exponent; keep only magnitudes a media fact can have.
            boolean absurd = Math.abs((long) value.scale()) > 18 || (long) value.precision() - value.scale() > 15;
            return absurd ? null : value;
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
