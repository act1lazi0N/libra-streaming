package com.libra.streaming.media.processing.infrastructure;

import com.libra.streaming.media.processing.application.MediaProber;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/**
 * The real ffprobe at the process boundary. A loopback listener counts connection attempts, so "the tool never
 * touched the network" is observed rather than inferred from an error message, and every claim comes with a
 * control run that proves the observation can fail. Nothing here is mocked.
 */
class FfprobeBoundaryIntegrationTest {
    private static Path executable;
    private static FfprobeMediaProber prober;
    @TempDir Path scratch;

    @BeforeAll
    static void locateTool() {
        executable = FfprobeLocator.locate();
        prober = new FfprobeMediaProber(executable, Duration.ofSeconds(30), 262144);
    }

    /** A loopback endpoint that accepts and drops every connection, counting them. */
    private static final class Listener implements AutoCloseable {
        private final ServerSocket server;
        private final AtomicInteger hits = new AtomicInteger();

        Listener() throws IOException {
            server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            Thread.ofVirtual().start(() -> {
                while (!server.isClosed()) {
                    try (var ignored = server.accept()) { hits.incrementAndGet(); }
                    catch (IOException closed) { return; }
                }
            });
        }

        int port() { return server.getLocalPort(); }

        int hits() { return hits.get(); }

        /** Connection attempts are made before the tool exits, but accepting them is asynchronous. */
        int hitsWithin(Duration wait) throws InterruptedException {
            long end = System.nanoTime() + wait.toNanos();
            while (hits.get() == 0 && System.nanoTime() < end) { Thread.sleep(25); }
            return hits.get();
        }

        @Override public void close() throws IOException { server.close(); }
    }

    private int raw(List<String> options, Path input) throws Exception {
        var command = new ArrayList<String>();
        command.add(executable.toString());
        command.addAll(List.of("-v", "error", "-hide_banner"));
        command.addAll(options);
        command.addAll(List.of("-print_format", "json", "-show_format", "-i", "file:" + input.toRealPath()));
        var process = new ProcessBuilder(command).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();
        return process.exitValue();
    }

    private Path concatTo(int port) throws IOException {
        return Files.writeString(scratch.resolve("remote.ffconcat"),
                "ffconcat version 1.0\nfile 'http://127.0.0.1:" + port + "/never.mp4'\n", StandardCharsets.US_ASCII);
    }

    private Path playlistTo(String target) throws IOException {
        return Files.writeString(scratch.resolve("reference.m3u8"),
                "#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXTINF:2,\n" + target + "\n#EXT-X-ENDLIST\n",
                StandardCharsets.US_ASCII);
    }

    @Test
    void theProtocolWhitelistAloneStopsAContainerFromOpeningTheNetwork() throws Exception {
        try (var listener = new Listener()) {
            var concat = concatTo(listener.port());
            // Control: with the demuxer's own safe mode off and http allowed on purpose, the tool does connect. (Without
            // any flag, ffprobe 9 already defaults a local input to "file,crypto,data"; the control must override it.)
            raw(List.of("-f", "concat", "-safe", "0", "-protocol_whitelist", "file,http,tcp"), concat);
            assertThat(listener.hitsWithin(Duration.ofSeconds(5))).as("control must reach the listener")
                    .isPositive();
            int before = listener.hits();
            // The explicit whitelist is the only barrier here: safe mode is off and the demuxer is concat.
            int exit = raw(List.of("-f", "concat", "-safe", "0", "-protocol_whitelist", "file"), concat);
            Thread.sleep(300);
            assertThat(exit).isNotZero();
            assertThat(listener.hits()).isEqualTo(before);
        }
    }

    @Test
    void theRealCommandNeverConnectsWhateverTheFileReferences() throws Exception {
        try (var listener = new Listener()) {
            for (var reference : List.of(concatTo(listener.port()),
                    playlistTo("http://127.0.0.1:" + listener.port() + "/never.mp4"))) {
                assertThatThrownBy(() -> prober.probe(reference, () -> false)).as(reference.getFileName().toString())
                        .isInstanceOf(MediaProber.Unreadable.class);
            }
            Thread.sleep(300);
            assertThat(listener.hits()).isZero();
        }
    }

    @Test
    void theForcedDemuxerClosesLocalFileIndirectionThatTheWhitelistCannot() throws Exception {
        var target = scratch.resolve("other.mp4");
        Files.copy(MediaFixtures.file("valid-160x96-silent.mp4"), target, StandardCopyOption.REPLACE_EXISTING);
        var playlist = playlistTo("other.mp4");
        // M19 command: the file protocol is whitelisted, so a local playlist is followed to another local file.
        assertThat(raw(List.of("-protocol_whitelist", "file"), playlist)).isZero();
        // M20 command: the playlist is not interpreted at all, whether or not its target exists.
        assertThatThrownBy(() -> prober.probe(playlist, () -> false)).isInstanceOf(MediaProber.Unreadable.class);
        Files.delete(target);
        assertThatThrownBy(() -> prober.probe(playlist, () -> false)).isInstanceOf(MediaProber.Unreadable.class);
    }

    @Test
    void anOutputBombFromTheRealBinaryIsCutOffAtTheBudget() throws Exception {
        var tiny = new FfprobeMediaProber(executable, Duration.ofSeconds(30), 1024);
        var valid = scratch.resolve("valid.mp4");
        Files.copy(MediaFixtures.file("valid-1080p-aac.mp4"), valid);
        // The real description of a perfectly good file is larger than 1 KiB, so it is refused, not truncated.
        assertThatThrownBy(() -> tiny.probe(valid, () -> false)).isInstanceOf(MediaProber.Unreadable.class);
        assertThat(prober.probe(valid, () -> false).tracks()).hasSize(2);
    }

    @Test
    void hostileFilesNeverLeaveAnffprobeProcessBehind() throws Exception {
        var baseline = running(executable);
        // Control: the detector does see a live process of that executable that was not there before.
        var control = new ProcessBuilder(ffmpeg().toString(), "-v", "error", "-f", "lavfi", "-i", "anullsrc", "-f",
                "null", "-").redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        try {
            long end = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (!control.isAlive() && System.nanoTime() < end) { Thread.sleep(25); }
            assertThat(running(ffmpeg())).as("control").contains(control.pid());
        } finally {
            control.destroyForcibly();
            control.waitFor(10, TimeUnit.SECONDS);
        }
        assertThat(running(ffmpeg())).doesNotContain(control.pid());

        var tiny = new FfprobeMediaProber(executable, Duration.ofSeconds(30), 1024);
        for (String name : new String[] {"corrupt-garbage.bin", "corrupt-truncated.mp4", "corrupt-empty.mp4",
                "invalid-container.mkv", "invalid-huge-metadata.mp4", "invalid-lying-headers.mp4",
                "invalid-seventy-streams.mp4"}) {
            var copy = scratch.resolve(name);
            Files.copy(MediaFixtures.file(name), copy);
            try { tiny.probe(copy, () -> false); } catch (MediaProber.Unreadable | MediaProber.Unavailable expected) { }
            assertThat(running(executable)).as(name).isSubsetOf(baseline);
        }
    }

    private static Path ffmpeg() {
        var configured = System.getenv("LIBRA_FFMPEG_PATH");
        if (configured != null && !configured.isBlank()) { return Path.of(configured).toAbsolutePath(); }
        return FfprobeLocator.locate().resolveSibling(
                System.getProperty("os.name").toLowerCase().contains("win") ? "ffmpeg.exe" : "ffmpeg");
    }

    /** Pids of live processes running this executable (Windows exposes no command line, only the image). */
    private static java.util.Set<Long> running(Path tool) {
        var name = tool.getFileName().toString();
        var pids = new java.util.HashSet<Long>();
        ProcessHandle.allProcesses().filter(handle -> handle.info().command()
                .map(command -> Path.of(command).getFileName().toString().equalsIgnoreCase(name)).orElse(false))
                .forEach(handle -> pids.add(handle.pid()));
        return pids;
    }
}
