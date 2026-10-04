package com.libra.streaming.media.processing.infrastructure;

import com.libra.streaming.media.processing.application.MediaProber;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/**
 * Process handling against scripted stand-ins for the executable. They prove the runner's contract (bounded
 * output, timeout, cancellation, exit status, no leakage); they say nothing about real ffprobe output, which the
 * fixture suite covers.
 */
class FfprobeMediaProberProcessTest {
    private static final boolean WINDOWS = System.getProperty("os.name").toLowerCase().contains("win");
    // The tool runs with an empty environment, so no PATH: stand-ins must name their helpers absolutely.
    private static final String PING = "\"%SystemRoot%\\System32\\PING.EXE\" -n 60 127.0.0.1 >nul";
    private static final String VALID = "{\"streams\":[],\"format\":{\"format_name\":\"mov,mp4\"}}";
    @TempDir Path directory;
    private Path input;

    @BeforeEach
    void setUp() throws IOException {
        input = Files.writeString(directory.resolve("input.part"), "bytes");
    }

    private Path script(String windows, String unix) throws IOException {
        var file = directory.resolve(WINDOWS ? "tool.cmd" : "tool.sh");
        Files.writeString(file, WINDOWS ? "@echo off\r\n" + windows + "\r\n" : "#!/bin/sh\n" + unix + "\n");
        assertThat(file.toFile().setExecutable(true)).isTrue();
        return file;
    }

    private FfprobeMediaProber prober(Path executable, Duration timeout, int limit) {
        return new FfprobeMediaProber(executable, timeout, limit);
    }

    @Test
    void readsBoundedStdoutOfASuccessfulRun() throws Exception {
        var tool = script("echo " + VALID, "echo '" + VALID + "'");
        var report = prober(tool, Duration.ofSeconds(20), 4096).probe(input, () -> false);
        assertThat(report.format().names()).isEqualTo("mov,mp4");
    }

    @Test
    void passesOnlyAFixedArgumentListWithAPinnedFileProtocol() throws Exception {
        var log = directory.resolve("args.txt");
        var tool = script("echo %* > \"" + log + "\" & echo " + VALID,
                "echo \"$@\" > '" + log + "'; echo '" + VALID + "'");
        prober(tool, Duration.ofSeconds(20), 4096).probe(input, () -> false);
        var arguments = Files.readString(log);
        assertThat(arguments).contains("-protocol_whitelist file").contains("-print_format json")
                .contains("-show_format").contains("-show_streams").contains("-i file:")
                .contains(input.toRealPath().toString());
    }

    @Test
    void theToolSeesNoInheritedEnvironment() throws Exception {
        var log = directory.resolve("environment.txt");
        var tool = script("set > \"" + log + "\" & echo " + VALID, "env > '" + log + "'; echo '" + VALID + "'");
        prober(tool, Duration.ofSeconds(20), 4096).probe(input, () -> false);
        assertThat(Files.readString(log)).doesNotContain("JAVA_HOME").doesNotContain("MAVEN");
    }

    @Test
    void aNonZeroExitIsAPermanentInputFailure() throws Exception {
        var tool = script("echo SECRET-DIAGNOSTIC 1>&2 & exit /b 1", "echo SECRET-DIAGNOSTIC >&2; exit 1");
        assertThatThrownBy(() -> prober(tool, Duration.ofSeconds(20), 4096).probe(input, () -> false))
                .isInstanceOf(MediaProber.Unreadable.class).hasMessageNotContaining("SECRET");
    }

    @Test
    void successfulExitWithMalformedJsonIsUnreadableWithoutTheOutput() throws Exception {
        var tool = script("echo SECRET-NOT-JSON", "echo SECRET-NOT-JSON");
        assertThatThrownBy(() -> prober(tool, Duration.ofSeconds(20), 4096).probe(input, () -> false))
                .isInstanceOf(MediaProber.Unreadable.class).hasMessageNotContaining("SECRET").hasNoCause();
    }

    @Test
    void excessiveOutputIsCutOffAndTheProcessEnds() throws Exception {
        var tool = script("echo " + "x".repeat(200) + "\r\n:loop\r\necho " + "x".repeat(200) + "\r\ngoto loop",
                "/usr/bin/yes " + "x".repeat(200));
        long started = System.nanoTime();
        assertThatThrownBy(() -> prober(tool, Duration.ofSeconds(30), 2048).probe(input, () -> false))
                .isInstanceOf(MediaProber.Unreadable.class);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(15));
    }

    @Test
    void aStalledToolIsKilledAtTheTimeoutAndIsRetryable() throws Exception {
        var marker = directory.resolve("started.txt");
        var tool = script("echo up > \"" + marker + "\"\r\n" + PING, "echo up > '" + marker + "'; /bin/sleep 60");
        long started = System.nanoTime();
        assertThatThrownBy(() -> prober(tool, Duration.ofSeconds(1), 4096).probe(input, () -> false))
                .isInstanceOf(MediaProber.Unavailable.class);
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
        assertThatThrownBy(() -> prober(tool, Duration.ofSeconds(30), 4096).probe(input, cancel::get))
                .isInstanceOf(InterruptedException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(15));
        assertNoSurvivor();
    }

    @Test
    void anAbsentOrNonExecutableToolIsRetryableAndLeaksNoPath() {
        var missing = directory.resolve("no-such-tool").toAbsolutePath();
        assertThatThrownBy(() -> prober(missing, Duration.ofSeconds(5), 4096).probe(input, () -> false))
                .isInstanceOf(MediaProber.Unavailable.class).hasMessageNotContaining(directory.toString());
    }

    @Test
    void aMissingInputFileIsRetryableNotACorruptVerdict() throws Exception {
        var tool = script("echo " + VALID, "echo '" + VALID + "'");
        assertThatThrownBy(() -> prober(tool, Duration.ofSeconds(5), 4096).probe(directory.resolve("gone"), () -> false))
                .isInstanceOf(MediaProber.Unavailable.class);
    }

    @Test
    void reportsTheFirstLineOfTheVersionBanner() throws Exception {
        var tool = script("echo ffprobe version 9.9-test Copyright\r\necho second line", "echo 'ffprobe version 9.9-test Copyright'; echo second");
        assertThat(prober(tool, Duration.ofSeconds(20), 4096).version()).isEqualTo("ffprobe version 9.9-test Copyright");
    }

    @Test
    void constructionRejectsUnsafeSettings() {
        assertThatThrownBy(() -> new FfprobeMediaProber(Path.of("ffprobe"), Duration.ofSeconds(5), 4096))
                .isInstanceOf(IllegalArgumentException.class);
        var absolute = directory.toAbsolutePath();
        assertThatThrownBy(() -> new FfprobeMediaProber(absolute, Duration.ZERO, 4096))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FfprobeMediaProber(absolute, Duration.ofMinutes(6), 4096))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FfprobeMediaProber(absolute, Duration.ofSeconds(5), 10))
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
