package com.libra.streaming.media.processing.application;

import com.libra.streaming.media.processing.domain.ProcessingFailure;
import com.libra.streaming.media.processing.domain.SourceMetadata;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.Set;

/**
 * The first-slice input policy, applied to what was actually found in the streams. Filename, MIME type and
 * the declared duration are never inputs. Missing or non-finite facts are {@code CORRUPT_INPUT}; facts that
 * are present but outside the policy are {@code UNSUPPORTED_MEDIA}. Both are permanent.
 *
 * <p>Extra streams are rejected, not ignored: exactly one video track, at most one audio track, and nothing
 * else (cover art, subtitles, timecode or data tracks), so selection never depends on stream order.
 */
public final class SourcePolicy {
    private static final Set<String> BRANDS = Set.of("isom", "iso2", "iso4", "iso5", "iso6", "mp41", "mp42", "avc1");
    private static final Set<String> HDR_TRANSFERS = Set.of("smpte2084", "arib-std-b67");
    private static final BigDecimal MAX_SECONDS = BigDecimal.valueOf(SourceMetadata.MAX_DURATION_MILLIS, 3);
    private static final long LIMIT = 100_000;
    /** Header facts may differ by encoder rounding and edit lists, not by the length of the clip. */
    private static final BigDecimal DURATION_SLACK = BigDecimal.valueOf(2);
    private static final BigDecimal DURATION_SHARE = new BigDecimal("0.1");
    private static final int FRAME_SLACK = 3;

    private SourcePolicy() {}

    /**
     * How far a duration derived from this source may stray from its container duration: the slack allowed between
     * its own tracks (two seconds, or a tenth of the clip if that is more). The rendition is held to the same bound.
     */
    public static long durationSlackMillis(long durationMillis) {
        return Math.max(DURATION_SLACK.movePointRight(3).longValueExact(),
                BigDecimal.valueOf(durationMillis).multiply(DURATION_SHARE).longValue());
    }

    /** Internal diagnostic only; never persisted, logged with input text, or returned over the API. */
    public enum Reason {
        NO_FORMAT, NOT_MP4, BRAND, DURATION_MISSING, DURATION_RANGE, STREAM_KIND, NO_VIDEO, EXTRA_VIDEO,
        EXTRA_AUDIO, VIDEO_CODEC, VIDEO_PROFILE, PIXEL_FORMAT, HDR, DIMENSIONS_MISSING, ASPECT_RATIO, ROTATION,
        RESOLUTION, FRAME_RATE_MISSING, FRAME_RATE_RANGE, AUDIO_CODEC, AUDIO_PARAMETERS, TRACK_DURATION,
        FRAME_COUNT
    }

    public sealed interface Verdict {
        record Accepted(SourceMetadata metadata) implements Verdict {}
        record Rejected(ProcessingFailure failure, Reason reason) implements Verdict {}
    }

    public static Verdict evaluate(ProbeReport report) {
        var format = report.format();
        if (format == null || format.names() == null) { return corrupt(Reason.NO_FORMAT); }
        var names = Arrays.asList(format.names().split(","));
        if (!names.contains("mp4") || !names.contains("mov")) { return unsupported(Reason.NOT_MP4); }
        if (format.majorBrand() == null || !BRANDS.contains(format.majorBrand().trim())) {
            return unsupported(Reason.BRAND);
        }
        var seconds = format.durationSeconds();
        if (seconds == null || seconds.signum() <= 0) { return corrupt(Reason.DURATION_MISSING); }
        if (seconds.compareTo(MAX_SECONDS) > 0) { return unsupported(Reason.DURATION_RANGE); }
        long millis = seconds.setScale(3, RoundingMode.HALF_UP).movePointRight(3).longValueExact();
        if (millis < 1) { return corrupt(Reason.DURATION_MISSING); }

        ProbeReport.Track video = null;
        ProbeReport.Track audio = null;
        for (var track : report.tracks()) {
            if (track.attachedPicture() || !("video".equals(track.type()) || "audio".equals(track.type()))) {
                return unsupported(Reason.STREAM_KIND);
            }
            if ("video".equals(track.type())) {
                if (video != null) { return unsupported(Reason.EXTRA_VIDEO); }
                video = track;
            } else {
                if (audio != null) { return unsupported(Reason.EXTRA_AUDIO); }
                audio = track;
            }
        }
        if (video == null) { return unsupported(Reason.NO_VIDEO); }
        if (!"h264".equals(video.codec())) { return unsupported(Reason.VIDEO_CODEC); }
        if (video.profile() == null || !SourceMetadata.VIDEO_PROFILES.contains(video.profile())) {
            return unsupported(Reason.VIDEO_PROFILE);
        }
        // 8-bit 4:2:0 only. HDR is judged on transfer and primaries, which is what separates it from SDR.
        if (!"yuv420p".equals(video.pixelFormat())) { return unsupported(Reason.PIXEL_FORMAT); }
        // Untagged colour is the common SDR case; Set.of rejects a null lookup, so test for it first.
        if ((video.colorTransfer() != null && HDR_TRANSFERS.contains(video.colorTransfer()))
                || "bt2020".equals(video.colorPrimaries())) {
            return unsupported(Reason.HDR);
        }
        if (video.width() == null || video.height() == null || video.width() < 1 || video.height() < 1) {
            return corrupt(Reason.DIMENSIONS_MISSING);
        }
        int codedWidth = video.width();
        int codedHeight = video.height();
        if (!SourceMetadata.withinLimits(codedWidth, codedHeight)) { return unsupported(Reason.RESOLUTION); }

        long[] ratio = rational(video.sampleAspectRatio(), ':', true);
        if (ratio == null) { return corrupt(Reason.ASPECT_RATIO); }
        int rotation = rotation(video.rotationDegrees());
        if (rotation < 0) { return unsupported(Reason.ROTATION); }
        // The pixel aspect ratio widens or narrows the picture; a quarter turn then swaps its axes.
        long squaredWidth = Math.round((double) codedWidth * ratio[0] / ratio[1]);
        if (squaredWidth < 1 || squaredWidth > LIMIT) { return unsupported(Reason.RESOLUTION); }
        boolean turned = rotation == 90 || rotation == 270;
        int displayWidth = (int) (turned ? codedHeight : squaredWidth);
        int displayHeight = (int) (turned ? squaredWidth : codedHeight);
        if (!SourceMetadata.withinLimits(displayWidth, displayHeight)) { return unsupported(Reason.RESOLUTION); }

        long[] rate = rational(video.averageFrameRate(), '/', false);
        if (rate == null) { return corrupt(Reason.FRAME_RATE_MISSING); }
        // One frame per second to thirty, exactly: 30000/1001 passes and 30001/1000 does not.
        if (rate[0] < rate[1] || rate[0] > (long) SourceMetadata.MAX_FRAMES_PER_SECOND * rate[1]) {
            return unsupported(Reason.FRAME_RATE_RANGE);
        }

        SourceMetadata.Audio audioFacts = null;
        if (audio != null) {
            if (!"aac".equals(audio.codec())) { return unsupported(Reason.AUDIO_CODEC); }
            if (audio.channels() == null || audio.sampleRate() == null) { return corrupt(Reason.AUDIO_PARAMETERS); }
            if (audio.channels() < 1 || audio.channels() > 8 || audio.sampleRate() < 8000
                    || audio.sampleRate() > 96000) {
                return unsupported(Reason.AUDIO_PARAMETERS);
            }
            audioFacts = new SourceMetadata.Audio(audio.channels(), audio.sampleRate());
        }
        // Last, so every earlier rejection keeps its own reason: the headers must tell one story about the clip.
        if (!tracksAgree(seconds, video, audio)) { return unsupported(Reason.TRACK_DURATION); }
        if (!framesFit(seconds, rate, video)) { return corrupt(Reason.FRAME_COUNT); }
        return new Verdict.Accepted(new SourceMetadata(millis, codedWidth, codedHeight, rotation, displayWidth,
                displayHeight, (int) rate[0], (int) rate[1], video.profile(), audioFacts));
    }

    /**
     * The container duration is what the limit is applied to, so each track must agree with it. Tracks of
     * clearly different lengths are a shape this slice does not transcode, not a corrupt file.
     */
    private static boolean tracksAgree(BigDecimal seconds, ProbeReport.Track video, ProbeReport.Track audio) {
        var allowed = DURATION_SLACK.max(seconds.multiply(DURATION_SHARE));
        for (var track : new ProbeReport.Track[] {video, audio}) {
            if (track != null && track.durationSeconds() != null
                    && seconds.subtract(track.durationSeconds()).abs().compareTo(allowed) > 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * The video frame count must fit the duration at the declared rate. Headers rewritten to say "one second"
     * would otherwise hide a clip with hundreds of frames from the duration limit; the sample table, not the
     * headers, is what a transcoder would have to process.
     */
    private static boolean framesFit(BigDecimal seconds, long[] rate, ProbeReport.Track video) {
        if (video.frameCount() == null) { return true; }
        double expected = seconds.doubleValue() * rate[0] / rate[1];
        return video.frameCount() <= expected * (1 + DURATION_SHARE.doubleValue()) + FRAME_SLACK;
    }

    /**
     * Normalizes to 0, 90, 180 or 270. Anything that is not a whole quarter turn (or is not finite) returns -1;
     * a track without a display matrix is upright.
     */
    private static int rotation(Double degrees) {
        if (degrees == null) { return 0; }
        if (degrees.isNaN() || degrees.isInfinite() || Math.abs(degrees) > 36_000) { return -1; }
        long whole = Math.round(degrees);
        if (Math.abs(degrees - whole) > 1e-6 || whole % 90 != 0) { return -1; }
        return (int) (((whole % 360) + 360) % 360);
    }

    /**
     * Parses "n{sep}d" into a reduced pair of positive numbers. A missing or placeholder ratio is square when
     * {@code absentIsSquare}; otherwise (frame rate) it is invalid. Returns null for zero, negative, or
     * malformed parts, and for values that do not fit an int after reduction.
     */
    private static long[] rational(String text, char separator, boolean absentIsSquare) {
        if (text == null || text.isBlank() || text.equals("N/A")) { return absentIsSquare ? new long[] {1, 1} : null; }
        int at = text.indexOf(separator);
        if (at < 1 || at == text.length() - 1 || text.length() > 24) { return null; }
        long numerator;
        long denominator;
        try {
            numerator = Long.parseLong(text.substring(0, at));
            denominator = Long.parseLong(text.substring(at + 1));
        } catch (NumberFormatException exception) {
            return null;
        }
        if (numerator == 0 && absentIsSquare) { return new long[] {1, 1}; } // "0:1" is the unspecified ratio.
        if (numerator < 1 || denominator < 1 || numerator > Integer.MAX_VALUE || denominator > Integer.MAX_VALUE) {
            return null;
        }
        long divisor = gcd(numerator, denominator);
        return new long[] {numerator / divisor, denominator / divisor};
    }

    private static long gcd(long a, long b) { return b == 0 ? a : gcd(b, a % b); }

    private static Verdict corrupt(Reason reason) { return new Verdict.Rejected(ProcessingFailure.CORRUPT_INPUT, reason); }

    private static Verdict unsupported(Reason reason) {
        return new Verdict.Rejected(ProcessingFailure.UNSUPPORTED_MEDIA, reason);
    }
}
