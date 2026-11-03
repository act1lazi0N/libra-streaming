package com.libra.streaming.media.processing.domain;

import java.util.Objects;

/**
 * The one rendition produced for a source, derived only from its validated {@link SourceMetadata}. The source's
 * display rectangle (pixel aspect ratio and rotation already applied) is fitted inside a box that follows its
 * orientation, keeping its aspect ratio and never being enlarged: {@value #LONG_SIDE} x {@value #SHORT_SIDE} for a
 * landscape or square clip, {@value #SHORT_SIDE} x {@value #LONG_SIDE} for a portrait one. Both sides are then rounded
 * down to even numbers, which is what 4:2:0 H.264 requires; the picture is scaled to that size, not cropped or padded.
 *
 * <p>Either box holds 3600 macroblocks, the most H.264 level 3.1 allows, so one level covers both orientations.
 */
public record RenditionPlan(int width, int height, int frameRateNumerator, int frameRateDenominator,
        int audioChannels) {
    public static final int LONG_SIDE = 1280;
    public static final int SHORT_SIDE = 720;
    public static final int SEGMENT_SECONDS = 4;
    public static final int MAX_VIDEO_KILOBITS = 2800;
    public static final int AUDIO_KILOBITS = 128;
    public static final int AUDIO_SAMPLE_RATE = 48_000;
    public static final int MAX_AUDIO_CHANNELS = 2;

    public RenditionPlan {
        if (width < 2 || height < 2 || Math.max(width, height) > LONG_SIDE
                || Math.min(width, height) > SHORT_SIDE || width % 2 != 0 || height % 2 != 0) {
            throw new IllegalArgumentException("Rendition size");
        }
        if (frameRateNumerator < 1 || frameRateDenominator < 1) { throw new IllegalArgumentException("Frame rate"); }
        if (audioChannels < 0 || audioChannels > MAX_AUDIO_CHANNELS) { throw new IllegalArgumentException("Audio"); }
    }

    public static RenditionPlan of(SourceMetadata source) {
        Objects.requireNonNull(source);
        long width = source.displayWidth();
        long height = source.displayHeight();
        boolean portrait = height > width;
        long boxWidth = portrait ? SHORT_SIDE : LONG_SIDE;
        long boxHeight = portrait ? LONG_SIDE : SHORT_SIDE;
        if (width > boxWidth || height > boxHeight) {
            // Compare the aspect ratios by cross-multiplying, so no rounding decides which side limits the fit.
            if (width * boxHeight > height * boxWidth) {
                height = Math.round((double) height * boxWidth / width);
                width = boxWidth;
            } else {
                width = Math.round((double) width * boxHeight / height);
                height = boxHeight;
            }
        }
        return new RenditionPlan(even(width), even(height), source.frameRateNumerator(),
                source.frameRateDenominator(), source.hasAudio()
                        ? Math.min(source.audio().channels(), MAX_AUDIO_CHANNELS) : 0);
    }

    public boolean hasAudio() { return audioChannels > 0; }

    /** Rounds down to an even number of at least two. */
    private static int even(long value) { return (int) Math.max(2, value & ~1L); }
}
