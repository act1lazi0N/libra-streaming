package com.libra.streaming.media.processing.application;

import java.nio.file.Path;
import java.util.List;

/**
 * A complete single-rendition HLS output in a local workspace, with a closed inventory: the master playlist, the
 * variant playlist and exactly the listed segments, all relative to {@link #directory()}. It is local scratch
 * only; nothing here is stored, selected, ready or playable.
 */
public record HlsOutput(Path directory, String masterName, String variantName, List<Segment> segments,
        int width, int height, boolean hasAudio, long durationMillis, long totalBytes,
        long peakBitsPerSecond, long averageBitsPerSecond) {
    public HlsOutput { segments = List.copyOf(segments); }

    /** One MPEG-TS segment: its relative name, the playlist's duration for it and its size on disk. */
    public record Segment(String name, long durationMillis, long bytes) {}
}
