package com.libra.streaming.media.processing.application;

import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

/**
 * A complete single-rendition HLS output in a local workspace, with a closed inventory: the master playlist, the
 * variant playlist and exactly the listed segments, all relative to {@link #directory()}. It is local scratch
 * only; nothing here is stored, selected, ready or playable.
 *
 * <p>It also pins the exact bytes the seal vouched for: the SHA-256 of every segment and the text of both playlists.
 * Whatever is later stored is compared against these, never against a fresh read of a workspace that could have
 * changed after the seal.
 */
public record HlsOutput(Path directory, String masterName, String variantName, List<Segment> segments,
        int width, int height, boolean hasAudio, long durationMillis, long totalBytes,
        long peakBitsPerSecond, long averageBitsPerSecond, String masterPlaylist) {
    public HlsOutput { segments = List.copyOf(segments); }

    /** The variant playlist this service writes for {@link #segments()}; it carries nothing else. */
    public String variantPlaylist() { return HlsPackage.variant(segments); }

    /**
     * One MPEG-TS segment: its relative name, the playlist's duration for it, its size on disk and the lowercase hex
     * SHA-256 of exactly those bytes when sealed.
     */
    public record Segment(String name, long durationMillis, long bytes, String sha256) {
        private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

        public Segment {
            if (sha256 == null || !SHA256.matcher(sha256).matches()) {
                throw new IllegalArgumentException("Segment digest");
            }
        }
    }
}
