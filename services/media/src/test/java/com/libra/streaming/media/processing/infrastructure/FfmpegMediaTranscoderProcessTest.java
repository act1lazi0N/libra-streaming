package com.libra.streaming.media.processing.infrastructure;

import com.libra.streaming.media.processing.application.MediaTranscoder;
import com.libra.streaming.media.processing.domain.RenditionPlan;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/**
 * Process handling against scripted stand-ins for ffmpeg. They prove the runner's contract (argument list, time and
 * output bounds, cancellation, exit classification, no leakage); real encoder output is covered by the fixture suite.
 */
class FfmpegMediaTranscoderProcessTest {
    private static final boolean WINDOWS = System.getProperty("os.name").toLowerCase().contains("win");
    // The tool runs with an empty environment, so no PATH: stand-ins must name their helpers absolutely.
    private static final String PING = "\"%SystemRoot%\\System32\\PING.EXE\" -n 60 127.0.0.1 >nul";
    private static final RenditionPlan PLAN = new RenditionPlan(1280, 720, 30, 1, 2);
    private static final long MIB = 1024 * 1024;
    @TempDir Path directory;
    private Path source;
    private Path workspace;

    @BeforeEach
    void setUp() throws IOException {
        source = Files.writeString(directory.resolve("source.part"), "bytes");
        workspace = Files.createDirectory(directory.resolve("workspace"));
    }

    private Path script(String windows, String unix) throws IOException {
        var file = directory.resolve(WINDOWS ? "tool.cmd" : "tool.sh");
        Files.writeString(file, WINDOWS ? "@echo off\r\n" + windows + "\r\n" : "#!/bin/sh\n" + unix + "\n");
        assertThat(file.toFile().setExecutable(true)).isTrue();
        return file;
    }

    private FfmpegMediaTranscoder transcoder(Path executable, Duration timeout, long budget) {
        return new FfmpegMediaTranscoder(executable, timeout, 2, budget);
    }

    private void run(Path executable, Duration timeout, long budget, AtomicBoolean cancelled) throws Exception {
        transcoder(executable, timeout, budget).transcode(source, PLAN, workspace, cancelled::get);
    }

    private void run(Path executable) throws Exception {
        run(executable, Duration.ofSeconds(20), 4 * MIB, new AtomicBoolean());
    }

    @Test
    void aCleanExitReturnsAndTheStandInSawTheStructuredArguments() throws Exception {
        var log = directory.resolve("args.txt");
        run(script("echo %* > \"" + log + "\"", "echo \"$@\" > '" + log + "'"));
        var arguments = Files.readString(log);
        assertThat(arguments).contains("-protocol_whitelist file").contains("-f mov").contains("-i file:")
                .contains("-c:v libx264").contains("-hls_time 4").contains("-hls_playlist_type vod")
                .contains(source.toRealPath().toString()).contains(workspace.toRealPath().toString());
    }

    @Test
    void theCommandIsFixedAndOnlyThePlanAndTwoGeneratedPathsVary() {
        var executable = directory.resolve("ffmpeg");
        var upright = FfmpegMediaTranscoder.command(executable, directory.resolve("a.part"), directory.resolve("one"), PLAN, 2);
        var hostile = FfmpegMediaTranscoder.command(executable, directory.resolve("-i http;x $(y).part"),
                directory.resolve("-f lavfi"), PLAN, 2);
        assertThat(hostile).hasSameSizeAs(upright);
        var differing = new java.util.ArrayList<Integer>();
        for (int index = 0; index < upright.size(); index++) {
            if (!upright.get(index).equals(hostile.get(index))) { differing.add(index); }
        }
        // Only the input operand, the segment template and the playlist path come from generated locations.
        assertThat(differing).containsExactly(upright.indexOf("-i") + 1,
                upright.indexOf("-hls_segment_filename") + 1, upright.size() - 1);
        assertThat(hostile).filteredOn("-i"::equals).hasSize(1);
        assertThat(hostile.get(hostile.indexOf("-i") + 1)).startsWith("file:");
    }

    @Test
    void theArgumentListFollowsThePlan() {
        var executable = directory.resolve("ffmpeg");
        var withAudio = FfmpegMediaTranscoder.command(executable, source, workspace, PLAN, 3);
        assertThat(withAudio).containsSubsequence("-map", "0:v:0", "-map", "0:a:0");
        assertThat(withAudio).contains("scale=1280:720:flags=bicubic,setsar=1,format=yuv420p");
        assertThat(withAudio).containsSubsequence("-c:v", "libx264", "-preset", "veryfast", "-profile:v", "main",
                "-level:v", "3.1");
        assertThat(withAudio).containsSubsequence("-maxrate", "2800k", "-bufsize", "5600k");
        assertThat(withAudio).containsSubsequence("-force_key_frames", "expr:gte(t,n_forced*4)");
        assertThat(withAudio).containsSubsequence("-threads", "3");
        assertThat(withAudio).containsSubsequence("-c:a", "aac", "-b:a", "128k", "-ar", "48000", "-ac", "2");
        assertThat(withAudio).containsSubsequence("-f", "hls", "-hls_time", "4", "-hls_playlist_type", "vod",
                "-hls_segment_type", "mpegts", "-hls_flags", "independent_segments", "-hls_list_size", "0");
        assertThat(withAudio).containsSubsequence("-sn", "-dn", "-map_metadata", "-1", "-map_chapters", "-1");
        assertThat(withAudio.getLast()).endsWith("rendition.m3u8");

        var silent = FfmpegMediaTranscoder.command(executable, source, workspace, new RenditionPlan(160, 96, 15, 1, 0), 2);
        assertThat(silent).doesNotContain("0:a:0", "-c:a", "aac", "-af");
        assertThat(silent).contains("scale=160:96:flags=bicubic,setsar=1,format=yuv420p");

        var mono = FfmpegMediaTranscoder.command(executable, source, workspace, new RenditionPlan(160, 96, 15, 1, 1), 2);
        assertThat(mono).containsSubsequence("-ac", "1");
    }

    @Test
    void theToolSeesNoInheritedEnvironment() throws Exception {
        var log = directory.resolve("environment.txt");
        run(script("set > \"" + log + "\"", "env > '" + log + "'"));
        assertThat(Files.readString(log)).doesNotContain("JAVA_HOME").doesNotContain("MAVEN");
    }

    @Test
    void chattyStdoutAndStderrNeverBlockOrLeak() throws Exception {
        run(script("for /L %%i in (1,1,2000) do echo " + "x".repeat(100) + "\r\necho SECRET 1>&2",
                "i=0; while [ $i -lt 2000 ]; do echo " + "x".repeat(100) + "; i=$((i+1)); done; echo SECRET >&2"));
    }

    @Test
    void anOrdinaryFailureStatusIsAPermanentDecodeVerdictWhateverItsNumber() throws Exception {
        // ffmpeg exits with a status derived from the error code (183 for undecodable data, 127 for a missing file).
        // On Windows the full negative AVERROR value is the exit status (-1094995529 is "invalid data").
        for (int status : WINDOWS ? List.of(1, 127, 183, 254, -2, -1094995529) : List.of(1, 127, 183, 254)) {
            var tool = script("echo SECRET-DIAGNOSTIC 1>&2 & exit /b " + status, "echo SECRET-DIAGNOSTIC >&2; exit " + status);
            assertThatThrownBy(() -> run(tool)).as("exit " + status)
                    .isInstanceOf(MediaTranscoder.Undecodable.class).hasMessageNotContaining("SECRET");
        }
    }

    @Test
    void aStatusThatMeansKilledOrCrashedIsRetryable() throws Exception {
        for (int status : List.of(134, 137, 139, 143)) {
            var tool = script("exit /b " + status, "exit " + status);
            assertThatThrownBy(() -> run(tool)).as("exit " + status).isInstanceOf(MediaTranscoder.Unavailable.class);
        }
        var crashed = script("exit /b -1073741819", "kill -SEGV $$");
        assertThatThrownBy(() -> run(crashed)).isInstanceOf(MediaTranscoder.Unavailable.class);
    }

    @Test
    void anOutputThatGrowsPastTheBudgetIsCutOffAndTheToolKilled() throws Exception {
        var big = Files.write(directory.resolve("big.bin"), new byte[(int) (2 * MIB)]);
        var target = workspace.resolve("segment-00000.ts");
        var tool = script("copy /Y /B \"" + big + "\" \"" + target + "\" >nul\r\n" + PING,
                "cp '" + big + "' '" + target + "'; /bin/sleep 60");
        long started = System.nanoTime();
        assertThatThrownBy(() -> run(tool, Duration.ofSeconds(30), MIB, new AtomicBoolean()))
                .isInstanceOf(MediaTranscoder.OutputTooLarge.class);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(15));
        assertNoSurvivor();
    }

    @Test
    void aRunawayNumberOfFilesIsCutOffToo() throws Exception {
        var tool = script("for /L %%i in (1,1,400) do type nul > \"" + workspace + "\\f%%i.ts\"\r\n" + PING,
                "i=0; while [ $i -lt 400 ]; do : > '" + workspace + "'/f$i.ts; i=$((i+1)); done; /bin/sleep 60");
        assertThatThrownBy(() -> run(tool, Duration.ofSeconds(30), 4 * MIB, new AtomicBoolean()))
                .isInstanceOf(MediaTranscoder.OutputTooLarge.class);
        assertNoSurvivor();
    }

    @Test
    void aStalledToolIsKilledAtTheTimeoutAndIsRetryable() throws Exception {
        var marker = directory.resolve("started.txt");
        var tool = script("echo up > \"" + marker + "\"\r\n" + PING, "echo up > '" + marker + "'; /bin/sleep 60");
        long started = System.nanoTime();
        assertThatThrownBy(() -> run(tool, Duration.ofSeconds(1), 4 * MIB, new AtomicBoolean()))
                .isInstanceOf(MediaTranscoder.Unavailable.class);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(20));
        assertThat(marker).exists();
        assertNoSurvivor();
    }

    @Test
    void cancellationInterruptsAndKillsTheProcessTree() throws Exception {
        var tool = script(PING, "/bin/sleep 60");
        var cancel = new AtomicBoolean();
        new Thread(() -> {
            try { Thread.sleep(500); } catch (InterruptedException ignored) { /* test helper */ }
            cancel.set(true);
        }).start();
        long started = System.nanoTime();
        assertThatThrownBy(() -> run(tool, Duration.ofSeconds(30), 4 * MIB, cancel))
                .isInstanceOf(InterruptedException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(15));
        assertNoSurvivor();
    }

    @Test
    void anAbsentToolOrAnUnusableInputOrWorkspaceIsRetryableAndLeaksNoPath() throws Exception {
        var missing = directory.resolve("no-such-tool").toAbsolutePath();
        assertThatThrownBy(() -> run(missing)).isInstanceOf(MediaTranscoder.Unavailable.class)
                .hasMessageNotContaining(directory.toString());
        var tool = script("exit /b 0", "exit 0");
        assertThatThrownBy(() -> transcoder(tool, Duration.ofSeconds(5), 4 * MIB)
                .transcode(directory.resolve("gone"), PLAN, workspace, () -> false))
                .isInstanceOf(MediaTranscoder.Unavailable.class);
        assertThatThrownBy(() -> transcoder(tool, Duration.ofSeconds(5), 4 * MIB)
                .transcode(source, PLAN, directory.resolve("no-workspace"), () -> false))
                .isInstanceOf(MediaTranscoder.Unavailable.class);
        // A workspace that already holds a file would put that file into the inventory.
        Files.writeString(workspace.resolve("stale.ts"), "x");
        assertThatThrownBy(() -> run(tool)).isInstanceOf(MediaTranscoder.Unavailable.class);
    }

    @Test
    void reportsTheFirstLineOfTheVersionBanner() throws Exception {
        var tool = script("echo ffmpeg version 9.9-test Copyright\r\necho second line",
                "echo 'ffmpeg version 9.9-test Copyright'; echo second");
        assertThat(transcoder(tool, Duration.ofSeconds(5), 4 * MIB).version()).isEqualTo("ffmpeg version 9.9-test Copyright");
    }

    @Test
    void constructionRejectsUnsafeSettings() {
        var absolute = directory.toAbsolutePath();
        assertThatThrownBy(() -> new FfmpegMediaTranscoder(Path.of("ffmpeg"), Duration.ofSeconds(5), 2, 4 * MIB))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FfmpegMediaTranscoder(absolute, Duration.ZERO, 2, 4 * MIB))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FfmpegMediaTranscoder(absolute, Duration.ofHours(2), 2, 4 * MIB))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FfmpegMediaTranscoder(absolute, Duration.ofSeconds(5), 0, 4 * MIB))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FfmpegMediaTranscoder(absolute, Duration.ofSeconds(5), 2, 1024))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** No leftover child may still hold the stand-in's command line (the 60-second sleep). */
    private static void assertNoSurvivor() throws InterruptedException {
        for (int i = 0; i < 20; i++) {
            boolean alive = ProcessHandle.allProcesses().anyMatch(handle -> handle.info().commandLine()
                    .map(line -> line.contains("-n 60 127.0.0.1") || line.contains("/bin/sleep 60")).orElse(false));
            if (!alive) { return; }
            Thread.sleep(250);
        }
        fail("A stand-in child process survived");
    }
}
