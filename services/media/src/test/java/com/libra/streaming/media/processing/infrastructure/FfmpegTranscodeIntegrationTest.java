package com.libra.streaming.media.processing.infrastructure;

import com.libra.streaming.media.processing.application.HlsOutput;
import com.libra.streaming.media.processing.application.HlsPackage;
import com.libra.streaming.media.processing.application.MediaTranscoder;
import com.libra.streaming.media.processing.application.SourcePolicy;
import com.libra.streaming.media.processing.domain.RenditionPlan;
import com.libra.streaming.media.processing.domain.SourceMetadata;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/**
 * The real encoder on generated fixtures: the real ffprobe judges each source, the plan is derived from that
 * verdict, the real ffmpeg encodes, and the real tools then decode and measure what came out. Mocks appear nowhere
 * here; storage and PostgreSQL are covered by the stage suite. A clean exit is never the assertion.
 */
class FfmpegTranscodeIntegrationTest {
    private static final long MIB = 1024 * 1024;
    @TempDir(cleanup = CleanupMode.NEVER) Path temp;

    @AfterEach
    void removeScratch() throws Exception { ScratchCleanup.deleteTree(temp); }
    private final Path ffmpeg = MediaFixtures.ffmpeg();
    private final Path ffprobe = FfprobeLocator.locate();
    private final FfprobeMediaProber prober = new FfprobeMediaProber(ffprobe, Duration.ofSeconds(30), 262_144);
    private final FfmpegMediaTranscoder transcoder = new FfmpegMediaTranscoder(ffmpeg, Duration.ofMinutes(5), 2,
            300 * MIB);

    private record Encoded(SourceMetadata source, RenditionPlan plan, HlsOutput output) {}

    private Encoded encode(String fixture) throws Exception {
        return encode(MediaFixtures.file(fixture), fixture);
    }

    private Encoded encode(Path source, String name) throws Exception {
        var verdict = SourcePolicy.evaluate(prober.probe(source, () -> false));
        assertThat(verdict).as(name).isInstanceOf(SourcePolicy.Verdict.Accepted.class);
        var metadata = ((SourcePolicy.Verdict.Accepted) verdict).metadata();
        var plan = RenditionPlan.of(metadata);
        var workspace = Files.createDirectory(temp.resolve("out-" + Math.abs(name.hashCode())));
        transcoder.transcode(source, plan, workspace, () -> false);
        return new Encoded(metadata, plan, HlsPackage.seal(workspace, plan));
    }

    private record Run(int exit, String text) {}

    private Run run(Path tool, String... arguments) throws Exception {
        var command = new java.util.ArrayList<String>(List.of(tool.toString()));
        command.addAll(List.of(arguments));
        var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        process.getOutputStream().close();
        var text = new String(process.getInputStream().readNBytes(1 << 20), StandardCharsets.UTF_8);
        assertThat(process.waitFor(120, TimeUnit.SECONDS)).isTrue();
        return new Run(process.exitValue(), text.strip());
    }

    /** Key/value facts the real ffprobe reads back from the playlist, not from our own bookkeeping. */
    private Map<String, String> facts(HlsOutput output, String stream, String entries) throws Exception {
        var run = run(ffprobe, "-v", "error", "-count_frames", "-select_streams", stream, "-show_entries",
                "stream=" + entries, "-of", "default=nw=1", output.directory().resolve(output.masterName()).toString());
        assertThat(run.exit()).as(run.text()).isZero();
        var values = new HashMap<String, String>();
        for (var line : run.text().split("\\R")) {
            int equals = line.indexOf('=');
            if (equals > 0) { values.put(line.substring(0, equals), line.substring(equals + 1)); }
        }
        return values;
    }

    private Map<String, String> video(HlsOutput output) throws Exception {
        return facts(output, "v:0", "codec_name,profile,level,pix_fmt,width,height,sample_aspect_ratio,r_frame_rate,"
                + "nb_read_frames");
    }

    private Map<String, String> audio(HlsOutput output) throws Exception {
        return facts(output, "a:0", "codec_name,profile,channels,sample_rate");
    }

    /** Decodes every packet of every segment through the master playlist: any decoder complaint fails the test. */
    private void assertDecodes(HlsOutput output) throws Exception {
        var run = run(ffmpeg, "-v", "error", "-xerror", "-i", output.directory().resolve(output.masterName()).toString(),
                "-f", "null", "-");
        assertThat(run.text()).isEmpty();
        assertThat(run.exit()).isZero();
    }

    private void assertSegmentsStartOnKeyframes(HlsOutput output) throws Exception {
        for (var segment : output.segments()) {
            var run = run(ffprobe, "-v", "error", "-select_streams", "v:0", "-read_intervals", "%+#1", "-show_entries",
                    "packet=flags", "-of", "csv=p=0", output.directory().resolve(segment.name()).toString());
            assertThat(run.text()).as(segment.name()).startsWith("K");
        }
    }

    private static double seconds(HlsOutput output) { return output.durationMillis() / 1000.0; }

    @Test
    void aHighResolutionSourceBecomesOneDecodableMain720pRenditionWithAacAudio() throws Exception {
        var encoded = encode("valid-1080p-aac.mp4");
        var output = encoded.output();

        assertThat(encoded.plan()).isEqualTo(new RenditionPlan(1280, 720, 30, 1, 2));
        assertDecodes(output);
        assertSegmentsStartOnKeyframes(output);
        assertThat(video(output)).containsEntry("codec_name", "h264").containsEntry("profile", "Main")
                .containsEntry("level", "31").containsEntry("pix_fmt", "yuv420p").containsEntry("width", "1280")
                .containsEntry("height", "720").containsEntry("r_frame_rate", "30/1");
        assertThat(audio(output)).containsEntry("codec_name", "aac").containsEntry("profile", "LC")
                .containsEntry("channels", "2").containsEntry("sample_rate", "48000");
        assertThat(seconds(output)).isBetween(0.9, 1.2);
        assertThat(Integer.parseInt(video(output).get("nb_read_frames"))).isBetween(29, 31);
        // The master names the variant by a relative name and states what is actually in it.
        var master = Files.readString(output.directory().resolve("master.m3u8"));
        assertThat(master.lines().filter(line -> !line.startsWith("#")).toList()).containsExactly("rendition.m3u8");
        assertThat(master).contains("RESOLUTION=1280x720").contains("CODECS=\"avc1.4d401f,mp4a.40.2\"");
    }

    @Test
    void aTwentySecondClipWithOneSourceKeyframeStillGetsFourSecondIndependentSegments() throws Exception {
        var encoded = encode("valid-720p-20s-aac.mp4");
        var output = encoded.output();

        assertThat(encoded.plan()).isEqualTo(new RenditionPlan(1280, 720, 30, 1, 2));
        assertThat(output.segments()).hasSize(5);
        assertThat(output.segments()).extracting(HlsOutput.Segment::durationMillis)
                .allSatisfy(duration -> assertThat(duration).isBetween(3900L, 4100L));
        assertThat(seconds(output)).isBetween(19.8, 20.3);
        assertDecodes(output);
        assertSegmentsStartOnKeyframes(output);
        assertThat(Integer.parseInt(video(output).get("nb_read_frames"))).isBetween(598, 602);
        // The rate cap bounds the output whatever the source was: video 2800k plus audio plus transport overhead.
        assertThat(output.peakBitsPerSecond()).isLessThan(3_700_000);
        assertThat(output.totalBytes()).isLessThan(20L * 3_700_000 / 8);
    }

    @Test
    void aNoisyHighBitrateSourceCannotPushTheOutputPastTheRateCap() throws Exception {
        var encoded = encode("valid-noise-1080p-10s.mp4");
        var output = encoded.output();

        // The source is about 44 MB for ten seconds; the output is bounded by rate, not by the input.
        assertThat(Files.size(MediaFixtures.file("valid-noise-1080p-10s.mp4"))).isGreaterThan(20L * MIB);
        assertDecodes(output);
        assertThat(output.peakBitsPerSecond()).isLessThan(3_700_000);
        assertThat(output.totalBytes()).isLessThan(10L * 3_700_000 / 8);
        // Even at the longest accepted duration the worst case measured here fits the output budget with room to spare.
        assertThat(output.peakBitsPerSecond() * 610 / 8).isLessThan(300 * MIB);
    }

    @Test
    void aSilentLowResolutionSourceIsNeitherEnlargedNorGivenAudio() throws Exception {
        var encoded = encode("valid-160x96-silent.mp4");
        var output = encoded.output();

        assertThat(encoded.plan()).isEqualTo(new RenditionPlan(160, 96, 15, 1, 0));
        assertDecodes(output);
        assertThat(video(output)).containsEntry("width", "160").containsEntry("height", "96")
                .containsEntry("r_frame_rate", "15/1");
        assertThat(facts(output, "a", "codec_name")).isEmpty();
        assertThat(seconds(output)).isBetween(1.9, 2.3);
        var master = Files.readString(output.directory().resolve("master.m3u8"));
        assertThat(master).contains("RESOLUTION=160x96").contains("CODECS=\"avc1.4d401f\"").doesNotContain("mp4a");
    }

    @Test
    void aSourceBelowTheBoxKeepsItsSizeAndItsOddFrameRate() throws Exception {
        var output = encode("valid-ntsc-rate.mp4").output();
        assertDecodes(output);
        assertThat(video(output)).containsEntry("width", "640").containsEntry("height", "360")
                .containsEntry("r_frame_rate", "30000/1001");
        assertThat(Integer.parseInt(video(output).get("nb_read_frames"))).isBetween(29, 31);
    }

    /**
     * Mean luma PSNR of the output's first frame against the source's first frame run through an explicit transform
     * with the tool's own rotation switched off. The reference does not use the tool's automatic rotation, so
     * agreement shows the declared orientation was really applied to the pixels, not only to the reported size.
     */
    private double psnrAgainst(Encoded encoded, Path source, String transform) throws Exception {
        var plan = encoded.plan();
        var run = run(ffmpeg, "-hide_banner", "-nostdin", "-i",
                encoded.output().directory().resolve("master.m3u8").toString(), "-noautorotate", "-f", "mov", "-i",
                source.toString(), "-filter_complex",
                "[0:v]setpts=PTS-STARTPTS,format=gray[o];[1:v]setpts=PTS-STARTPTS," + transform + ",scale="
                        + plan.width() + ":" + plan.height() + ":flags=bicubic,format=gray[r];[o][r]psnr",
                "-frames:v", "1", "-f", "null", "-");
        var matcher = java.util.regex.Pattern.compile("average:(inf|[0-9.]+)").matcher(run.text());
        assertThat(matcher.find()).as(run.text()).isTrue();
        return matcher.group(1).equals("inf") ? 99 : Double.parseDouble(matcher.group(1));
    }

    @Test
    void aRotatedSourceIsEncodedUprightWithoutADisplayMatrix() throws Exception {
        // The correct reference for each declared display rotation: ffmpeg's -display_rotation is counterclockwise.
        record Case(String fixture, String transform, int width, int height) {}
        var all = List.of("null", "transpose=1", "transpose=2", "hflip,vflip");
        for (var rotated : List.of(new Case("valid-rotated-90.mp4", "transpose=2", 720, 1280),
                new Case("valid-rotated-270.mp4", "transpose=1", 720, 1280),
                new Case("valid-rotated-180.mp4", "hflip,vflip", 1280, 720))) {
            var source = MediaFixtures.file(rotated.fixture());
            var encoded = encode(rotated.fixture());
            if (rotated.width() == 720) {
                assertThat(encoded.source().displayWidth()).as(rotated.fixture()).isEqualTo(720);
                assertThat(encoded.source().displayHeight()).as(rotated.fixture()).isEqualTo(1280);
            }
            assertDecodes(encoded.output());
            // A portrait 720x1280 clip fits the portrait box exactly: it stays upright and is neither shrunk nor squashed.
            assertThat(video(encoded.output())).as(rotated.fixture())
                    .containsEntry("width", Integer.toString(rotated.width()))
                    .containsEntry("height", Integer.toString(rotated.height()));
            var sideData = run(ffprobe, "-v", "error", "-select_streams", "v:0", "-show_entries",
                    "stream_side_data=rotation", "-of", "default=nw=1",
                    encoded.output().directory().resolve("segment-00000.ts").toString());
            assertThat(sideData.text()).as(rotated.fixture()).doesNotContain("rotation");
            // Pixels: the declared transform matches closely, and every other orientation is clearly different.
            for (var candidate : all) {
                double psnr = psnrAgainst(encoded, source, candidate);
                if (candidate.equals(rotated.transform())) {
                    assertThat(psnr).as(rotated.fixture() + " vs " + candidate).isGreaterThan(30);
                } else {
                    assertThat(psnr).as(rotated.fixture() + " vs " + candidate).isLessThan(20);
                }
            }
        }
    }

    @Test
    void aPortraitSourceAndAnAnamorphicSourceFollowTheirDisplayShape() throws Exception {
        var portrait = encode("valid-portrait-1080x1920.mp4");
        assertDecodes(portrait.output());
        assertThat(video(portrait.output())).containsEntry("width", "720").containsEntry("height", "1280");

        var anamorphic = encode("valid-anamorphic.mp4");
        assertDecodes(anamorphic.output());
        // 1440x1080 with 4:3 pixels displays as 1920x1080 and is delivered as square-pixel 1280x720.
        assertThat(video(anamorphic.output())).containsEntry("width", "1280").containsEntry("height", "720");
        assertThat(video(anamorphic.output()).get("sample_aspect_ratio")).isIn("1:1", "N/A");
    }

    @Test
    void audioChannelsAreKeptUpToStereoAndTheSampleRateIsNormalized() throws Exception {
        var mono = encode("valid-mono-44100.mp4").output();
        assertDecodes(mono);
        assertThat(audio(mono)).containsEntry("codec_name", "aac").containsEntry("channels", "1")
                .containsEntry("sample_rate", "48000");
        var surround = encode("valid-surround-6ch.mp4").output();
        assertDecodes(surround);
        assertThat(audio(surround)).containsEntry("channels", "2").containsEntry("sample_rate", "48000");
    }

    @Test
    void theLongestAcceptedClipYieldsTheExpectedClosedInventory() throws Exception {
        var encoded = encode("valid-600s.mp4");
        var output = encoded.output();

        assertThat(encoded.plan()).isEqualTo(new RenditionPlan(64, 64, 1, 1, 0));
        assertThat(output.segments()).hasSize(150);
        assertThat(seconds(output)).isBetween(599.0, 601.0);
        assertDecodes(output);
        assertThat(video(output)).containsEntry("width", "64").containsEntry("height", "64");
        try (var files = Files.list(output.directory())) {
            assertThat(files.map(path -> path.getFileName().toString()).collect(Collectors.toSet()))
                    .hasSize(152).contains("master.m3u8", "rendition.m3u8");
        }
    }

    @Test
    void theDensestAcceptedIndexIsEncodedFrameForFrame() throws Exception {
        var output = encode("valid-dense-600s.mp4").output();
        assertDecodes(output);
        assertThat(Integer.parseInt(video(output).get("nb_read_frames"))).isBetween(17_990, 18_010);
        assertThat(output.segments()).hasSize(150);
    }

    @Test
    void aHostileFileNameChangesNothingAboutTheCommandOrTheOutput() throws Exception {
        var named = Files.copy(MediaFixtures.file("valid-160x96-silent.mp4"), temp.resolve("-i http;&echo x $(y) 'z'.mp4"));
        var output = encode(named, "hostile-name").output();
        assertDecodes(output);
        try (var files = Files.list(output.directory())) {
            assertThat(files.map(path -> path.getFileName().toString()).collect(Collectors.toSet()))
                    .allMatch(name -> name.matches("master\\.m3u8|rendition\\.m3u8|segment-\\d{5}\\.ts"));
        }
    }

    @Test
    void aPlaylistDisguisedAsAnMp4IsNeverFollowedToTheNetwork() throws Exception {
        var connections = new AtomicInteger();
        try (var listener = new ServerSocket(0, 5, InetAddress.getLoopbackAddress())) {
            var acceptor = Thread.ofVirtual().start(() -> {
                try { while (true) { listener.accept().close(); connections.incrementAndGet(); } }
                catch (IOException closed) { /* the listener was closed by the test */ }
            });
            var disguised = Files.writeString(temp.resolve("disguised.mp4"), "#EXTM3U\n#EXT-X-TARGETDURATION:4\n"
                    + "#EXTINF:4,\nhttp://127.0.0.1:" + listener.getLocalPort() + "/segment.ts\n#EXT-X-ENDLIST\n");
            var workspace = Files.createDirectory(temp.resolve("disguised-out"));
            var plan = new RenditionPlan(160, 96, 15, 1, 0);

            assertThatThrownBy(() -> transcoder.transcode(disguised, plan, workspace, () -> false))
                    .isInstanceOf(MediaTranscoder.Undecodable.class);

            Thread.sleep(300);
            assertThat(connections).hasValue(0);
            acceptor.interrupt();
        }
    }

    @Test
    void anUndecodableSourceIsAPermanentVerdictAndLeavesNoRendition() throws Exception {
        var plan = new RenditionPlan(160, 96, 15, 1, 0);
        for (var fixture : List.of("corrupt-truncated.mp4", "corrupt-garbage.bin", "corrupt-empty.mp4")) {
            var workspace = Files.createDirectory(temp.resolve("bad-" + fixture));
            assertThatThrownBy(() -> transcoder.transcode(MediaFixtures.file(fixture), plan, workspace, () -> false))
                    .as(fixture).isInstanceOf(MediaTranscoder.Undecodable.class);
            assertThat(workspace.resolve("master.m3u8")).as(fixture).doesNotExist();
        }
    }

    @Test
    void anOutputBudgetBelowWhatTheClipNeedsStopsTheEncodeWithoutALeftoverProcess() throws Exception {
        var tight = new FfmpegMediaTranscoder(ffmpeg, Duration.ofMinutes(5), 2, MIB);
        var source = MediaFixtures.file("valid-720p-20s-aac.mp4");
        var plan = RenditionPlan.of(((SourcePolicy.Verdict.Accepted) SourcePolicy.evaluate(
                prober.probe(source, () -> false))).metadata());
        var workspace = Files.createDirectory(temp.resolve("tight"));
        var before = ffmpegProcesses();

        assertThatThrownBy(() -> tight.transcode(source, plan, workspace, () -> false))
                .isInstanceOf(MediaTranscoder.OutputTooLarge.class);

        assertThat(ffmpegProcesses()).isSubsetOf(before);
    }

    @Test
    void aTimeoutAndACancellationBothKillTheRealEncoder() throws Exception {
        var source = MediaFixtures.file("valid-dense-600s.mp4");
        var plan = new RenditionPlan(1280, 720, 30, 1, 0);
        var before = ffmpegProcesses();

        var slow = new FfmpegMediaTranscoder(ffmpeg, Duration.ofMillis(1), 2, 300 * MIB);
        var first = Files.createDirectory(temp.resolve("timeout"));
        assertThatThrownBy(() -> slow.transcode(source, plan, first, () -> false))
                .isInstanceOf(MediaTranscoder.Unavailable.class);
        assertThat(ffmpegProcesses()).isSubsetOf(before);

        var cancel = new AtomicBoolean();
        var second = Files.createDirectory(temp.resolve("cancel"));
        Thread.ofVirtual().start(() -> {
            try { Thread.sleep(400); } catch (InterruptedException ignored) { /* test helper */ }
            cancel.set(true);
        });
        assertThatThrownBy(() -> transcoder.transcode(source, plan, second, cancel::get))
                .isInstanceOf(InterruptedException.class);
        assertThat(ffmpegProcesses()).isSubsetOf(before);
    }

    /** Process ids whose executable is the ffmpeg under test; a survivor would be one that was not there before. */
    private Set<Long> ffmpegProcesses() throws Exception {
        Thread.sleep(500); // a killed process needs a moment to leave the process table
        return ProcessHandle.allProcesses().filter(handle -> handle.info().command()
                .map(command -> Path.of(command).equals(ffmpeg)).orElse(false)).map(ProcessHandle::pid)
                .collect(Collectors.toSet());
    }
}
