package com.libra.streaming.media.processing.application;

import com.libra.streaming.media.processing.domain.RenditionPlan;
import com.libra.streaming.media.processing.domain.SourceMetadata;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Turns the files an encoder left in a workspace into a checked, closed HLS inventory and writes both playlists that
 * describe it. The encoder's own exit status is never trusted on its own: the variant playlist must use only the tags
 * and names this pipeline produces, every segment it names must exist and be whole MPEG-TS packets, nothing else may
 * be in the directory, and together the segments must last as long as the probed source. Anything unexpected is
 * {@link Malformed}. The encoder's variant playlist is then replaced by one written from the checked inventory, so
 * neither playlist that leaves the workspace contains a byte the encoder chose.
 */
public final class HlsPackage {
    public static final String MASTER = "master.m3u8";
    public static final String VARIANT = "rendition.m3u8";
    public static final String SEGMENT_TEMPLATE = "segment-%05d.ts";
    /** A 600 second source at four seconds a segment is 150; the rest is slack for encoder keyframe placement. */
    public static final int MAX_SEGMENTS = 300;
    static final long MAX_PLAYLIST_BYTES = 256 * 1024;
    private static final Pattern SEGMENT = Pattern.compile("segment-(\\d{5})\\.ts");
    private static final Pattern EXTINF = Pattern.compile("#EXTINF:(\\d{1,5}(?:\\.\\d{1,9})?),");
    private static final Pattern TARGET = Pattern.compile("#EXT-X-TARGETDURATION:(\\d{1,3})");
    private static final Pattern VERSION = Pattern.compile("#EXT-X-VERSION:\\d{1,2}");
    /** An MPEG-TS packet: what a segment is made of, each one starting with the sync byte. */
    static final int TS_PACKET = 188;
    private static final int TS_SYNC = 0x47;

    private HlsPackage() {}

    /** The encoder's output is not a complete, well-formed rendition. Carries no file content or path. */
    public static final class Malformed extends RuntimeException {
        public Malformed(String reason) { super(reason, null, false, false); }
    }

    /**
     * Verifies the variant playlist and its segments in {@code directory} against the plan and the probed source's
     * duration, then rewrites {@link #VARIANT} and writes {@link #MASTER}. The returned inventory describes exactly
     * what was found, so a later stage stores that and nothing else.
     *
     * <p>The duration check is the backstop for an encoder that stopped early without saying so: ffmpeg finalizes a
     * well-formed, shorter playlist when it catches a signal or meets a damaged tail. It allows the slack the source
     * policy allows between a source's own tracks, since the rendition follows the video track while the source's
     * duration is that of its longest track.
     */
    public static HlsOutput seal(Path directory, RenditionPlan plan, long sourceDurationMillis) {
        if (sourceDurationMillis < 1 || sourceDurationMillis > SourceMetadata.MAX_DURATION_MILLIS) {
            throw new IllegalArgumentException("Source duration");
        }
        try {
            var entries = parseVariant(read(directory.resolve(VARIANT)));
            var segments = new ArrayList<HlsOutput.Segment>();
            long duration = 0;
            long segmentBytes = 0;
            long peak = 0;
            for (var entry : entries) {
                var file = directory.resolve(entry.name());
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) { throw new Malformed("SEGMENT_MISSING"); }
                long size = Files.size(file);
                if (size < 1) { throw new Malformed("SEGMENT_EMPTY"); }
                requireTransportPackets(file, size);
                segments.add(new HlsOutput.Segment(entry.name(), entry.durationMillis(), size));
                duration += entry.durationMillis();
                segmentBytes += size;
                peak = Math.max(peak, bitsPerSecond(size, entry.durationMillis()));
            }
            if (Math.abs(duration - sourceDurationMillis) > SourcePolicy.durationSlackMillis(sourceDurationMillis)) {
                throw new Malformed("DURATION");
            }
            requireNoStrangers(directory, segments);
            long average = bitsPerSecond(segmentBytes, duration);
            long bandwidth = Math.max(peak, average);
            var variant = variant(segments);
            var master = master(plan, bandwidth, average);
            Files.writeString(directory.resolve(VARIANT), variant, StandardCharsets.UTF_8);
            Files.writeString(directory.resolve(MASTER), master, StandardCharsets.UTF_8);
            long bytes = segmentBytes + variant.getBytes(StandardCharsets.UTF_8).length
                    + master.getBytes(StandardCharsets.UTF_8).length;
            return new HlsOutput(directory, MASTER, VARIANT, segments, plan.width(), plan.height(),
                    plan.hasAudio(), duration, bytes, bandwidth, average);
        } catch (IOException exception) {
            throw new Malformed("IO");
        }
    }

    /**
     * The variant playlist as this service states it: the checked segments in order with their durations to the
     * millisecond, and a target duration of at least one second. ffmpeg declares a target duration of 0 for a clip
     * shorter than half a second, which the specification tolerates and players need not.
     */
    static String variant(List<HlsOutput.Segment> segments) {
        long longest = segments.stream().mapToLong(HlsOutput.Segment::durationMillis).max().orElseThrow();
        var text = new StringBuilder("#EXTM3U\n#EXT-X-VERSION:6\n#EXT-X-TARGETDURATION:")
                .append(Math.max(1, Math.ceilDiv(longest, 1000)))
                .append("\n#EXT-X-MEDIA-SEQUENCE:0\n#EXT-X-PLAYLIST-TYPE:VOD\n#EXT-X-INDEPENDENT-SEGMENTS\n");
        for (var segment : segments) {
            long millis = segment.durationMillis();
            text.append("#EXTINF:").append(String.format(Locale.ROOT, "%d.%03d", millis / 1000, millis % 1000))
                    .append(",\n").append(segment.name()).append('\n');
        }
        return text.append("#EXT-X-ENDLIST\n").toString();
    }

    /** A single-variant master playlist whose only reference is the relative variant name. */
    static String master(RenditionPlan plan, long bandwidth, long averageBandwidth) {
        var rate = BigDecimal.valueOf(plan.frameRateNumerator())
                .divide(BigDecimal.valueOf(plan.frameRateDenominator()), 3, RoundingMode.HALF_UP).toPlainString();
        // Main profile level 3.1 and AAC-LC are what the encoder is told to produce; see FfmpegMediaTranscoder.
        var codecs = plan.hasAudio() ? "avc1.4d401f,mp4a.40.2" : "avc1.4d401f";
        return "#EXTM3U\n#EXT-X-VERSION:6\n#EXT-X-INDEPENDENT-SEGMENTS\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=" + bandwidth + ",AVERAGE-BANDWIDTH=" + averageBandwidth
                + ",RESOLUTION=" + plan.width() + "x" + plan.height() + ",FRAME-RATE=" + rate
                + ",CODECS=\"" + codecs + "\"\n" + VARIANT + "\n";
    }

    record Entry(String name, long durationMillis) {}

    /** Accepts only the VOD playlist this pipeline asks the encoder for; every other tag or URI is rejected. */
    static List<Entry> parseVariant(String text) {
        var lines = text.stripTrailing().split("\r?\n", -1);
        if (!lines[0].equals("#EXTM3U")) { throw new Malformed("HEADER"); }
        var entries = new ArrayList<Entry>();
        boolean vod = false;
        boolean ended = false;
        boolean independent = false;
        int target = -1;
        BigDecimal pending = null;
        for (int index = 1; index < lines.length; index++) {
            var line = lines[index];
            // Trailing line breaks were stripped above; a blank line anywhere else is not something we produce.
            if (line.isEmpty()) { throw new Malformed("BLANK"); }
            if (ended) { throw new Malformed("AFTER_END"); }
            if (pending != null) {
                var matcher = SEGMENT.matcher(line);
                if (!matcher.matches() || Integer.parseInt(matcher.group(1)) != entries.size()) {
                    throw new Malformed("SEGMENT_NAME");
                }
                // A positive duration under half a millisecond still counts as one, so no segment is ever 0.000.
                long millis = pending.movePointRight(3).setScale(0, RoundingMode.HALF_UP).longValueExact();
                entries.add(new Entry(line, Math.max(1, millis)));
                pending = null;
            } else if (line.startsWith("#EXTINF:")) {
                var matcher = EXTINF.matcher(line);
                if (!matcher.matches()) { throw new Malformed("EXTINF"); }
                pending = new BigDecimal(matcher.group(1));
                if (pending.signum() <= 0) { throw new Malformed("EXTINF"); }
            } else if (line.equals("#EXT-X-ENDLIST")) {
                ended = true;
            } else if (line.equals("#EXT-X-PLAYLIST-TYPE:VOD")) {
                vod = true;
            } else if (line.equals("#EXT-X-INDEPENDENT-SEGMENTS")) {
                independent = true;
            } else if (line.equals("#EXT-X-MEDIA-SEQUENCE:0") || VERSION.matcher(line).matches()) {
                continue;
            } else if (TARGET.matcher(line).matches()) {
                target = Integer.parseInt(line.substring(line.indexOf(':') + 1));
            } else {
                // Byte ranges, maps, keys, discontinuities, URIs, comments and anything else are not ours.
                throw new Malformed("TAG");
            }
        }
        // ffmpeg declares a target of 0 for a clip under half a second; the rewritten playlist states at least 1.
        if (!vod || !ended || !independent || pending != null || entries.isEmpty() || target < 0) {
            throw new Malformed("INCOMPLETE");
        }
        if (entries.size() > MAX_SEGMENTS) { throw new Malformed("SEGMENT_COUNT"); }
        for (var entry : entries) {
            // HLS requires the rounded segment duration not to exceed the target duration.
            if (Math.round(entry.durationMillis() / 1000.0) > target) { throw new Malformed("TARGET_DURATION"); }
        }
        return entries;
    }

    /**
     * A segment ffmpeg finished is a whole number of transport packets starting with the sync byte. A write cut short
     * by a full disk usually is not, so a playlist that survived the cut cannot vouch for a truncated segment.
     */
    private static void requireTransportPackets(Path file, long size) throws IOException {
        if (size % TS_PACKET != 0) { throw new Malformed("SEGMENT_PACKETS"); }
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            if (input.read() != TS_SYNC) { throw new Malformed("SEGMENT_PACKETS"); }
        }
    }

    private static void requireNoStrangers(Path directory, List<HlsOutput.Segment> segments) throws IOException {
        Set<String> expected = new HashSet<>();
        expected.add(VARIANT);
        expected.add(MASTER);
        segments.forEach(segment -> expected.add(segment.name()));
        try (Stream<Path> files = Files.list(directory)) {
            for (var file : (Iterable<Path>) files::iterator) {
                if (!expected.contains(file.getFileName().toString())
                        || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                    throw new Malformed("UNEXPECTED_FILE");
                }
            }
        }
    }

    private static String read(Path playlist) throws IOException {
        if (!Files.isRegularFile(playlist, LinkOption.NOFOLLOW_LINKS)) { throw new Malformed("PLAYLIST_MISSING"); }
        if (Files.size(playlist) > MAX_PLAYLIST_BYTES) { throw new Malformed("PLAYLIST_SIZE"); }
        return Files.readString(playlist, StandardCharsets.UTF_8);
    }

    private static long bitsPerSecond(long bytes, long millis) {
        return Math.ceilDiv(bytes * 8 * 1000, millis);
    }
}
