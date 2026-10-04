package com.libra.streaming.media.processing.application;

import com.libra.streaming.media.processing.domain.ProcessingFailure;
import com.libra.streaming.media.processing.domain.SourceMetadata;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** The input policy on synthetic reports; real ffprobe output is covered by the fixture integration test. */
class SourcePolicyTest {
    private static ProbeReport.Format format() {
        return new ProbeReport.Format("mov,mp4,m4a,3gp,3g2,mj2", "isom", new BigDecimal("10.000000"));
    }

    private static ProbeReport.Track video(UnaryOperator<VideoBuilder> change) {
        return change.apply(new VideoBuilder()).build();
    }

    private static ProbeReport.Track video() { return new VideoBuilder().build(); }

    private static ProbeReport.Track audio(String codec, Integer channels, Integer rate) {
        return new ProbeReport.Track("audio", codec, "LC", null, null, null, null, "0/0", null, null, null, false,
                channels, rate);
    }

    private static SourceMetadata accepted(ProbeReport report) {
        var verdict = SourcePolicy.evaluate(report);
        assertThat(verdict).isInstanceOf(SourcePolicy.Verdict.Accepted.class);
        return ((SourcePolicy.Verdict.Accepted) verdict).metadata();
    }

    private static void assertRejected(ProbeReport report, ProcessingFailure failure, SourcePolicy.Reason reason) {
        assertThat(SourcePolicy.evaluate(report)).isEqualTo(new SourcePolicy.Verdict.Rejected(failure, reason));
    }

    private static ProbeReport report(ProbeReport.Track... tracks) { return new ProbeReport(format(), List.of(tracks)); }

    @Test
    void acceptsSilentAndAudibleHighAndLowResolutionSources() {
        var high = accepted(report(video(v -> v.size(1920, 1080).rate("30/1")), audio("aac", 2, 48000)));
        assertThat(high.displayWidth()).isEqualTo(1920);
        assertThat(high.displayHeight()).isEqualTo(1080);
        assertThat(high.audio()).isEqualTo(new SourceMetadata.Audio(2, 48000));
        assertThat(high.durationMillis()).isEqualTo(10_000);

        var low = accepted(report(video(v -> v.size(160, 90).rate("15/1"))));
        assertThat(low.hasAudio()).isFalse();
        assertThat(low.displayWidth()).isEqualTo(160);
        assertThat(low.frameRateNumerator()).isEqualTo(15);
    }

    @Test
    void normalizesQuarterTurnsAndTheirSign() {
        var upright = accepted(report(video(v -> v.size(1920, 1080))));
        assertThat(upright.rotation()).isZero();
        for (double degrees : new double[] {90, -270, 450}) {
            var turned = accepted(report(video(v -> v.size(1920, 1080).rotation(degrees))));
            assertThat(turned.rotation()).isEqualTo(90);
            assertThat(turned.displayWidth()).isEqualTo(1080);
            assertThat(turned.displayHeight()).isEqualTo(1920);
            assertThat(turned.codedWidth()).isEqualTo(1920);
        }
        var half = accepted(report(video(v -> v.size(1280, 720).rotation(-180))));
        assertThat(half.rotation()).isEqualTo(180);
        assertThat(half.displayWidth()).isEqualTo(1280);
        assertThat(accepted(report(video(v -> v.size(1280, 720).rotation(-90)))).rotation()).isEqualTo(270);
    }

    @Test
    void rejectsRotationsThatAreNotWholeQuarterTurnsOrNotFinite() {
        for (double degrees : new double[] {45, 90.4, Double.NaN, Double.POSITIVE_INFINITY, 1e9}) {
            assertRejected(report(video(v -> v.rotation(degrees))), ProcessingFailure.UNSUPPORTED_MEDIA,
                    SourcePolicy.Reason.ROTATION);
        }
    }

    @Test
    void appliesThePixelAspectRatioBeforeJudgingTheResolution() {
        var wide = accepted(report(video(v -> v.size(1440, 1080).sar("4:3"))));
        assertThat(wide.displayWidth()).isEqualTo(1920);
        assertThat(wide.codedWidth()).isEqualTo(1440);
        assertRejected(report(video(v -> v.size(1920, 1080).sar("4:3"))), ProcessingFailure.UNSUPPORTED_MEDIA,
                SourcePolicy.Reason.RESOLUTION);
        // A placeholder or unspecified ratio is square; a malformed one is a damaged file.
        for (String square : new String[] {null, "N/A", "0:1", "1:1", ""}) {
            assertThat(accepted(report(video(v -> v.size(640, 360).sar(square)))).displayWidth()).isEqualTo(640);
        }
        for (String broken : new String[] {"1:0", "-1:2", "x:y", "1", "1:", ":1", "1:2:3"}) {
            assertRejected(report(video(v -> v.sar(broken))), ProcessingFailure.CORRUPT_INPUT,
                    SourcePolicy.Reason.ASPECT_RATIO);
        }
    }

    @Test
    void enforcesResolutionInEitherOrientation() {
        accepted(report(video(v -> v.size(1080, 1920))));
        assertRejected(report(video(v -> v.size(1921, 1080))), ProcessingFailure.UNSUPPORTED_MEDIA,
                SourcePolicy.Reason.RESOLUTION);
        assertRejected(report(video(v -> v.size(1920, 1081))), ProcessingFailure.UNSUPPORTED_MEDIA,
                SourcePolicy.Reason.RESOLUTION);
        assertRejected(report(video(v -> v.size(3840, 2160))), ProcessingFailure.UNSUPPORTED_MEDIA,
                SourcePolicy.Reason.RESOLUTION);
        assertRejected(report(video(v -> v.size(15, 90))), ProcessingFailure.UNSUPPORTED_MEDIA,
                SourcePolicy.Reason.RESOLUTION);
        assertRejected(report(video(v -> v.size(Integer.MAX_VALUE, 90))), ProcessingFailure.UNSUPPORTED_MEDIA,
                SourcePolicy.Reason.RESOLUTION);
        for (int[] bad : new int[][] {{0, 90}, {-1, 90}, {640, 0}}) {
            assertRejected(report(video(v -> v.size(bad[0], bad[1]))), ProcessingFailure.CORRUPT_INPUT,
                    SourcePolicy.Reason.DIMENSIONS_MISSING);
        }
        assertRejected(report(video(v -> v.size(null, 90))), ProcessingFailure.CORRUPT_INPUT,
                SourcePolicy.Reason.DIMENSIONS_MISSING);
    }

    @Test
    void frameRateIsExactAndFinite() {
        assertThat(accepted(report(video(v -> v.rate("30000/1001")))).frameRateDenominator()).isEqualTo(1001);
        assertThat(accepted(report(video(v -> v.rate("60/2")))).frameRateNumerator()).isEqualTo(30);
        assertThat(accepted(report(video(v -> v.rate("1/1")))).frameRateNumerator()).isEqualTo(1);
        for (String fast : new String[] {"30001/1000", "60/1", "25000000/1"}) {
            assertRejected(report(video(v -> v.rate(fast))), ProcessingFailure.UNSUPPORTED_MEDIA,
                    SourcePolicy.Reason.FRAME_RATE_RANGE);
        }
        assertRejected(report(video(v -> v.rate("999/1000"))), ProcessingFailure.UNSUPPORTED_MEDIA,
                SourcePolicy.Reason.FRAME_RATE_RANGE);
        for (String broken : new String[] {null, "0/0", "0/1", "30/0", "-30/1", "N/A", "abc", "30", "9999999999/1"}) {
            assertRejected(report(video(v -> v.rate(broken))), ProcessingFailure.CORRUPT_INPUT,
                    SourcePolicy.Reason.FRAME_RATE_MISSING);
        }
    }

    @Test
    void durationComesFromTheStreamsAndIsBounded() {
        assertThat(accepted(new ProbeReport(new ProbeReport.Format("mov,mp4", "mp42", new BigDecimal("600.000")),
                List.of(video()))).durationMillis()).isEqualTo(600_000);
        assertThat(accepted(new ProbeReport(new ProbeReport.Format("mov,mp4", "mp42", new BigDecimal("0.0014")),
                List.of(video()))).durationMillis()).isEqualTo(1);
        assertThat(accepted(new ProbeReport(new ProbeReport.Format("mov,mp4", "mp42", new BigDecimal("9.9995")),
                List.of(video()))).durationMillis()).isEqualTo(10_000);
        for (String tooLong : new String[] {"600.001", "86400", "1E+9"}) {
            assertRejected(new ProbeReport(new ProbeReport.Format("mov,mp4", "isom", new BigDecimal(tooLong)),
                    List.of(video())), ProcessingFailure.UNSUPPORTED_MEDIA, SourcePolicy.Reason.DURATION_RANGE);
        }
        for (BigDecimal missing : new BigDecimal[] {null, BigDecimal.ZERO, new BigDecimal("-1"), new BigDecimal("0.0001")}) {
            assertRejected(new ProbeReport(new ProbeReport.Format("mov,mp4", "isom", missing), List.of(video())),
                    ProcessingFailure.CORRUPT_INPUT, SourcePolicy.Reason.DURATION_MISSING);
        }
    }

    @Test
    void containerMustBeMp4NotOtherMovFamilyMembers() {
        assertRejected(new ProbeReport(new ProbeReport.Format("matroska,webm", "isom", BigDecimal.TEN),
                List.of(video())), ProcessingFailure.UNSUPPORTED_MEDIA, SourcePolicy.Reason.NOT_MP4);
        assertRejected(new ProbeReport(new ProbeReport.Format("mov,mp4,m4a,3gp,3g2,mj2", "qt  ", BigDecimal.TEN),
                List.of(video())), ProcessingFailure.UNSUPPORTED_MEDIA, SourcePolicy.Reason.BRAND);
        assertRejected(new ProbeReport(new ProbeReport.Format("mov,mp4,m4a,3gp,3g2,mj2", null, BigDecimal.TEN),
                List.of(video())), ProcessingFailure.UNSUPPORTED_MEDIA, SourcePolicy.Reason.BRAND);
        assertRejected(new ProbeReport(null, List.of(video())), ProcessingFailure.CORRUPT_INPUT,
                SourcePolicy.Reason.NO_FORMAT);
        assertRejected(new ProbeReport(new ProbeReport.Format(null, "isom", BigDecimal.TEN), List.of(video())),
                ProcessingFailure.CORRUPT_INPUT, SourcePolicy.Reason.NO_FORMAT);
        for (String brand : new String[] {"isom", "iso2", "iso4", "iso5", "iso6", "mp41", "mp42", "avc1"}) {
            accepted(new ProbeReport(new ProbeReport.Format("mov,mp4", brand, BigDecimal.TEN), List.of(video())));
        }
    }

    @Test
    void videoMustBeEightBitSdrH264InAnAcceptedProfile() {
        assertRejected(report(video(v -> v.codec("hevc"))), ProcessingFailure.UNSUPPORTED_MEDIA,
                SourcePolicy.Reason.VIDEO_CODEC);
        assertRejected(report(video(v -> v.profile("High 10"))), ProcessingFailure.UNSUPPORTED_MEDIA,
                SourcePolicy.Reason.VIDEO_PROFILE);
        assertRejected(report(video(v -> v.profile(null))), ProcessingFailure.UNSUPPORTED_MEDIA,
                SourcePolicy.Reason.VIDEO_PROFILE);
        for (String format : new String[] {"yuv420p10le", "yuv444p", "yuv422p", "yuvj420p", null}) {
            assertRejected(report(video(v -> v.pixelFormat(format))), ProcessingFailure.UNSUPPORTED_MEDIA,
                    SourcePolicy.Reason.PIXEL_FORMAT);
        }
        assertRejected(report(video(v -> v.transfer("smpte2084"))), ProcessingFailure.UNSUPPORTED_MEDIA,
                SourcePolicy.Reason.HDR);
        assertRejected(report(video(v -> v.transfer("arib-std-b67"))), ProcessingFailure.UNSUPPORTED_MEDIA,
                SourcePolicy.Reason.HDR);
        assertRejected(report(video(v -> v.primaries("bt2020"))), ProcessingFailure.UNSUPPORTED_MEDIA,
                SourcePolicy.Reason.HDR);
        // Untagged colour (no transfer, no primaries) is ordinary SDR, as ffprobe omits those fields.
        accepted(report(video(v -> v.transfer(null).primaries(null))));
        for (String profile : new String[] {"Baseline", "Constrained Baseline", "Main", "High"}) {
            accepted(report(video(v -> v.profile(profile).transfer("bt709").primaries("bt709"))));
        }
    }

    @Test
    void extraOrUnusableStreamsAreRejectedRegardlessOfOrder() {
        var cover = new ProbeReport.Track("video", "mjpeg", null, 300, 300, "yuvj420p", null, "0/0", null, null,
                null, true, null, null);
        var subtitles = new ProbeReport.Track("subtitle", "mov_text", null, null, null, null, null, null, null,
                null, null, false, null, null);
        var timecode = new ProbeReport.Track("data", "none", null, null, null, null, null, null, null, null, null,
                false, null, null);
        for (var extra : List.of(cover, subtitles, timecode)) {
            assertRejected(report(video(), audio("aac", 2, 44100), extra), ProcessingFailure.UNSUPPORTED_MEDIA,
                    SourcePolicy.Reason.STREAM_KIND);
            assertRejected(report(extra, video()), ProcessingFailure.UNSUPPORTED_MEDIA,
                    SourcePolicy.Reason.STREAM_KIND);
        }
        assertRejected(report(video(), video()), ProcessingFailure.UNSUPPORTED_MEDIA, SourcePolicy.Reason.EXTRA_VIDEO);
        assertRejected(report(audio("aac", 2, 44100), video(), audio("aac", 2, 44100)),
                ProcessingFailure.UNSUPPORTED_MEDIA, SourcePolicy.Reason.EXTRA_AUDIO);
        assertRejected(report(audio("aac", 2, 44100)), ProcessingFailure.UNSUPPORTED_MEDIA,
                SourcePolicy.Reason.NO_VIDEO);
        assertRejected(report(), ProcessingFailure.UNSUPPORTED_MEDIA, SourcePolicy.Reason.NO_VIDEO);
        // Audio first is still accepted: selection is by kind, not by index.
        assertThat(accepted(report(audio("aac", 1, 44100), video())).audio().channels()).isEqualTo(1);
    }

    @Test
    void audioMustBeAacWithSaneParameters() {
        assertRejected(report(video(), audio("mp3", 2, 44100)), ProcessingFailure.UNSUPPORTED_MEDIA,
                SourcePolicy.Reason.AUDIO_CODEC);
        assertRejected(report(video(), audio("aac", 9, 44100)), ProcessingFailure.UNSUPPORTED_MEDIA,
                SourcePolicy.Reason.AUDIO_PARAMETERS);
        assertRejected(report(video(), audio("aac", 2, 4000)), ProcessingFailure.UNSUPPORTED_MEDIA,
                SourcePolicy.Reason.AUDIO_PARAMETERS);
        assertRejected(report(video(), audio("aac", null, 44100)), ProcessingFailure.CORRUPT_INPUT,
                SourcePolicy.Reason.AUDIO_PARAMETERS);
        assertRejected(report(video(), audio("aac", 2, null)), ProcessingFailure.CORRUPT_INPUT,
                SourcePolicy.Reason.AUDIO_PARAMETERS);
    }

    @Test
    void everyAcceptedResultSatisfiesTheDomainInvariants() {
        // The domain record re-checks the policy; accepting must never trip it.
        var rates = new String[] {"1/1", "24000/1001", "25/1", "30/1", "30000/1001"};
        for (String rate : rates) {
            for (double rotation : new double[] {0, 90, 180, 270}) {
                var metadata = accepted(report(video(v -> v.size(1280, 720).rate(rate).rotation(rotation))));
                assertThat(SourceMetadata.withinLimits(metadata.displayWidth(), metadata.displayHeight())).isTrue();
            }
        }
        assertThat(new ArrayList<>(SourceMetadata.VIDEO_PROFILES)).hasSize(4);
    }

    /** A valid 1280x720 H.264 SDR track that each test bends in exactly one way. */
    private static final class VideoBuilder {
        private String codec = "h264";
        private String profile = "High";
        private Integer width = 1280;
        private Integer height = 720;
        private String pixelFormat = "yuv420p";
        private String sar = "1:1";
        private String rate = "30/1";
        private String transfer = "bt709";
        private String primaries = "bt709";
        private Double rotation;

        VideoBuilder codec(String value) { codec = value; return this; }
        VideoBuilder profile(String value) { profile = value; return this; }
        VideoBuilder size(Integer w, Integer h) { width = w; height = h; return this; }
        VideoBuilder pixelFormat(String value) { pixelFormat = value; return this; }
        VideoBuilder sar(String value) { sar = value; return this; }
        VideoBuilder rate(String value) { rate = value; return this; }
        VideoBuilder transfer(String value) { transfer = value; return this; }
        VideoBuilder primaries(String value) { primaries = value; return this; }
        VideoBuilder rotation(double value) { rotation = value; return this; }

        ProbeReport.Track build() {
            return new ProbeReport.Track("video", codec, profile, width, height, pixelFormat, sar, rate, transfer,
                    primaries, rotation, false, null, null);
        }
    }
}
