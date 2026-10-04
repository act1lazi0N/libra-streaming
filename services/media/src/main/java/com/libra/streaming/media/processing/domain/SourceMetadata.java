package com.libra.streaming.media.processing.domain;

import java.util.Set;

/**
 * Technical facts about one frozen source, after the first-slice policy accepted it. The container is always
 * MP4 and the video codec always H.264, so neither is carried. Display dimensions already include the pixel
 * aspect ratio and the rotation, so downstream sizing never reads raw stream metadata.
 */
public record SourceMetadata(long durationMillis, int codedWidth, int codedHeight, int rotation,
        int displayWidth, int displayHeight, int frameRateNumerator, int frameRateDenominator,
        String videoProfile, Audio audio) {
    public static final long MAX_DURATION_MILLIS = 600_000;
    public static final int MAX_LONG_SIDE = 1920;
    public static final int MAX_SHORT_SIDE = 1080;
    public static final int MIN_SIDE = 16;
    public static final int MAX_FRAMES_PER_SECOND = 30;
    public static final Set<String> VIDEO_PROFILES = Set.of("Baseline", "Constrained Baseline", "Main", "High");

    /** Optional AAC track; a silent source has none and stays silent. */
    public record Audio(int channels, int sampleRate) {
        public Audio {
            if (channels < 1 || channels > 8) { throw new IllegalArgumentException("Audio channels"); }
            if (sampleRate < 8000 || sampleRate > 96000) { throw new IllegalArgumentException("Audio sample rate"); }
        }
    }

    public SourceMetadata {
        if (durationMillis < 1 || durationMillis > MAX_DURATION_MILLIS) { throw new IllegalArgumentException("Duration"); }
        if (!withinLimits(codedWidth, codedHeight) || !withinLimits(displayWidth, displayHeight)) {
            throw new IllegalArgumentException("Dimensions");
        }
        if (rotation != 0 && rotation != 90 && rotation != 180 && rotation != 270) {
            throw new IllegalArgumentException("Rotation");
        }
        if (frameRateNumerator < 1 || frameRateDenominator < 1
                || frameRateNumerator < frameRateDenominator
                || (long) frameRateNumerator > (long) MAX_FRAMES_PER_SECOND * frameRateDenominator) {
            throw new IllegalArgumentException("Frame rate");
        }
        if (!VIDEO_PROFILES.contains(videoProfile)) { throw new IllegalArgumentException("Video profile"); }
    }

    public boolean hasAudio() { return audio != null; }

    /** Square-aspect-ratio-corrected, rotation-corrected rectangle that fits 1080p in either orientation. */
    public static boolean withinLimits(int width, int height) {
        int longSide = Math.max(width, height);
        int shortSide = Math.min(width, height);
        return shortSide >= MIN_SIDE && longSide <= MAX_LONG_SIDE && shortSide <= MAX_SHORT_SIDE;
    }
}
