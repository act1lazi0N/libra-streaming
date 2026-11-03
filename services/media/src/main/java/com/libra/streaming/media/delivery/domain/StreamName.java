package com.libra.streaming.media.delivery.domain;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The closed vocabulary of names a protected stream can request: the master playlist, the rendition playlist and the
 * numbered MPEG-TS segments the pipeline writes. Anything else (another extension, a path, a dot segment, an encoded
 * character, a segment number past the encoder's cap) names nothing, so a request can only ever select an object
 * directly under the authorized generation's prefix.
 */
public record StreamName(String value, Kind kind) {
    /** The encoder's segment cap (the processing pipeline's {@code HlsPackage.MAX_SEGMENTS}). */
    public static final int MAX_SEGMENTS = 300;
    /** Playlists are written by this service and are far smaller (the pipeline refuses one above this). */
    public static final long MAX_PLAYLIST_BYTES = 256 * 1024;
    /** The largest object Media stores at all. */
    public static final long MAX_SEGMENT_BYTES = 268435456;
    private static final Pattern SEGMENT = Pattern.compile("segment-(\\d{5})\\.ts");

    public enum Kind {
        MASTER("application/vnd.apple.mpegurl", MAX_PLAYLIST_BYTES),
        RENDITION("application/vnd.apple.mpegurl", MAX_PLAYLIST_BYTES),
        SEGMENT("video/mp2t", MAX_SEGMENT_BYTES);

        private final String contentType;
        private final long maxBytes;

        Kind(String contentType, long maxBytes) {
            this.contentType = contentType;
            this.maxBytes = maxBytes;
        }

        public String contentType() { return contentType; }
        public long maxBytes() { return maxBytes; }
    }

    public StreamName {
        if (kind == null || kindOf(value) != kind) { throw new IllegalArgumentException("Stream name"); }
    }

    public static Optional<StreamName> parse(String name) {
        return Optional.ofNullable(kindOf(name)).map(kind -> new StreamName(name, kind));
    }

    private static Kind kindOf(String name) {
        if (name == null) { return null; }
        if (name.equals("master.m3u8")) { return Kind.MASTER; }
        if (name.equals("rendition.m3u8")) { return Kind.RENDITION; }
        var segment = SEGMENT.matcher(name);
        return segment.matches() && Integer.parseInt(segment.group(1)) < MAX_SEGMENTS ? Kind.SEGMENT : null;
    }
}
