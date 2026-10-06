package com.libra.streaming.media.processing.application;

import java.math.BigDecimal;
import java.util.List;

/**
 * What the probe observed, before any policy is applied. Fields are as raw as is safe: unparseable or
 * non-finite values arrive as null so the policy, not the parser, decides what they mean.
 */
public record ProbeReport(Format format, List<Track> tracks) {
    public ProbeReport { tracks = List.copyOf(tracks); }

    /** {@code durationSeconds} is null when absent, "N/A", NaN or infinite. */
    public record Format(String names, String majorBrand, BigDecimal durationSeconds) {}

    /**
     * {@code rotationDegrees} is the display-matrix rotation, null when the track declares none, and NaN when the
     * file declares more than one rotation and they disagree. {@code durationSeconds} and {@code frameCount} are
     * the track's own claims, kept so the policy can compare them with the container and with each other.
     */
    public record Track(String type, String codec, String profile, Integer width, Integer height,
            String pixelFormat, String sampleAspectRatio, String averageFrameRate, String colorTransfer,
            String colorPrimaries, Double rotationDegrees, boolean attachedPicture, Integer channels,
            Integer sampleRate, BigDecimal durationSeconds, Integer frameCount) {
        /** A track whose own duration and frame count are unknown. */
        public Track(String type, String codec, String profile, Integer width, Integer height,
                String pixelFormat, String sampleAspectRatio, String averageFrameRate, String colorTransfer,
                String colorPrimaries, Double rotationDegrees, boolean attachedPicture, Integer channels,
                Integer sampleRate) {
            this(type, codec, profile, width, height, pixelFormat, sampleAspectRatio, averageFrameRate,
                    colorTransfer, colorPrimaries, rotationDegrees, attachedPicture, channels, sampleRate, null, null);
        }
    }
}
