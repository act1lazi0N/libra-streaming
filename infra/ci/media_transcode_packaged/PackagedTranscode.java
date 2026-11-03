package com.libra.streaming.media.processing.infrastructure;

import com.libra.streaming.media.processing.application.HlsPackage;
import com.libra.streaming.media.processing.application.MediaTranscoder;
import com.libra.streaming.media.processing.application.SourcePolicy;
import com.libra.streaming.media.processing.domain.RenditionPlan;
import com.libra.streaming.media.processing.domain.SourceMetadata;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * M21/M22 evidence harness, run inside the packaged Media image (see docs/media-transcode-m21.md and
 * docs/media-encoding-hardening-m22.md). It lives in the production package because the runner is package-private,
 * and it is compiled against the classes unpacked from the image's own jar, so the code under test is the shipped code
 * and the tools are the image's ffprobe and ffmpeg.
 *
 * <pre>
 * matrix  ffmpeg ffprobe fixtureDirectory outputDirectory        probe, encode, seal and decode every accepted fixture
 * limits  ffmpeg ffprobe fixture outputDirectory timeoutMillis   one encode under a deliberately short deadline
 * cancel  ffmpeg ffprobe fixture outputDirectory afterMillis     one encode cancelled while it runs
 * rejects ffmpeg ffprobe fixtureDirectory outputDirectory        every damaged-tail fixture: probe verdict, encode outcome
 * signal  ffmpeg ffprobe fixture outputDirectory TERM|KILL       the encoder signalled from outside after two segments
 * full    ffmpeg ffprobe fixture outputDirectory                 one encode into a directory on a tiny file system
 * </pre>
 *
 * Every mode ends with the container's own accounting: peak memory, peak process count and CPU throttling.
 */
public final class PackagedTranscode {
    private static final long BUDGET = 300L * 1024 * 1024;

    private PackagedTranscode() {}

    public static void main(String[] args) throws Exception {
        var ffmpeg = Path.of(args[1]);
        var ffprobe = Path.of(args[2]);
        switch (args[0]) {
            case "matrix" -> matrix(ffmpeg, ffprobe, Path.of(args[3]), Path.of(args[4]));
            case "limits" -> limits(ffmpeg, ffprobe, Path.of(args[3]), Path.of(args[4]), Long.parseLong(args[5]));
            case "cancel" -> cancel(ffmpeg, ffprobe, Path.of(args[3]), Path.of(args[4]), Long.parseLong(args[5]));
            case "rejects" -> rejects(ffmpeg, ffprobe, Path.of(args[3]), Path.of(args[4]));
            case "signal" -> signal(ffmpeg, ffprobe, Path.of(args[3]), Path.of(args[4]), args[5]);
            case "full" -> full(ffmpeg, ffprobe, Path.of(args[3]), Path.of(args[4]));
            default -> throw new IllegalArgumentException(args[0]);
        }
        accounting();
    }

    private static SourceMetadata metadata(Path ffprobe, Path file) throws Exception {
        var prober = new FfprobeMediaProber(ffprobe, Duration.ofSeconds(30), 262144);
        var verdict = SourcePolicy.evaluate(prober.probe(file, () -> false));
        return verdict instanceof SourcePolicy.Verdict.Accepted accepted ? accepted.metadata() : null;
    }

    private static RenditionPlan plan(Path ffprobe, Path file) throws Exception {
        var metadata = metadata(ffprobe, file);
        return metadata == null ? null : RenditionPlan.of(metadata);
    }

    private static void matrix(Path ffmpeg, Path ffprobe, Path fixtures, Path out) throws Exception {
        var transcoder = new FfmpegMediaTranscoder(ffmpeg, Duration.ofMinutes(10), 2, BUDGET);
        System.out.println("TOOL " + transcoder.version());
        System.out.println("TOOL " + new FfprobeMediaProber(ffprobe, Duration.ofSeconds(5), 4096).version());
        try (Stream<Path> files = Files.list(fixtures)) {
            for (var file : files.filter(p -> p.getFileName().toString().startsWith("valid-")).sorted().toList()) {
                var name = file.getFileName().toString();
                var metadata = metadata(ffprobe, file);
                if (metadata == null) { System.out.println(String.format("%-34s NOT-ACCEPTED-BY-POLICY", name)); continue; }
                var plan = RenditionPlan.of(metadata);
                var workspace = Files.createDirectories(out.resolve(name));
                long started = System.nanoTime();
                try {
                    transcoder.transcode(file, plan, workspace, () -> false);
                    var output = HlsPackage.seal(workspace, plan, metadata.durationMillis());
                    long millis = (System.nanoTime() - started) / 1_000_000;
                    var master = workspace.resolve(output.masterName()).toString();
                    var decode = run(ffmpeg, "-v", "error", "-xerror", "-i", master, "-f", "null", "-");
                    var video = run(ffprobe, "-v", "error", "-count_frames", "-select_streams", "v:0", "-show_entries",
                            "stream=codec_name,profile,level,width,height,r_frame_rate,nb_read_frames", "-of",
                            "compact=nk=1:p=0", master);
                    var audio = run(ffprobe, "-v", "error", "-select_streams", "a:0", "-show_entries",
                            "stream=codec_name,profile,channels,sample_rate", "-of", "compact=nk=1:p=0", master);
                    System.out.println(String.format("%-30s ENCODED %6d ms plan=%dx%d audio=%d segments=%d duration=%dms"
                            + " bytes=%d peak=%d decode=%s video=[%s] audio=[%s]", name, millis, plan.width(),
                            plan.height(), plan.audioChannels(), output.segments().size(), output.durationMillis(),
                            output.totalBytes(), output.peakBitsPerSecond(),
                            decode.isEmpty() ? "CLEAN" : "ERRORS:" + decode, video, audio.isEmpty() ? "none" : audio));
                } catch (MediaTranscoder.Unavailable | MediaTranscoder.Undecodable | MediaTranscoder.OutputTooLarge
                        | HlsPackage.Malformed failure) {
                    System.out.println(String.format("%-30s FAILED %s", name, failure.getMessage()));
                }
            }
        }
        System.out.println("DESCENDANTS_AFTER " + ProcessHandle.current().descendants().count());
    }

    private static void limits(Path ffmpeg, Path ffprobe, Path file, Path out, long timeoutMillis) throws Exception {
        var plan = plan(ffprobe, file);
        var transcoder = new FfmpegMediaTranscoder(ffmpeg, Duration.ofMillis(timeoutMillis), 2, BUDGET);
        long started = System.nanoTime();
        String outcome;
        try {
            transcoder.transcode(file, plan, Files.createDirectories(out.resolve("limits")), () -> false);
            outcome = "COMPLETED";
        } catch (MediaTranscoder.Unavailable unavailable) {
            outcome = "UNAVAILABLE";
        }
        System.out.println(String.format("%-34s %s  %d ms", file.getFileName(), outcome,
                (System.nanoTime() - started) / 1_000_000));
        System.out.println("DESCENDANTS_AFTER " + ProcessHandle.current().descendants().count());
    }

    private static void cancel(Path ffmpeg, Path ffprobe, Path file, Path out, long afterMillis) throws Exception {
        var plan = plan(ffprobe, file);
        var transcoder = new FfmpegMediaTranscoder(ffmpeg, Duration.ofMinutes(10), 2, BUDGET);
        var cancelled = new AtomicBoolean();
        Thread.ofVirtual().start(() -> {
            try { Thread.sleep(afterMillis); } catch (InterruptedException ignored) { return; }
            cancelled.set(true);
        });
        long started = System.nanoTime();
        String outcome;
        try {
            transcoder.transcode(file, plan, Files.createDirectories(out.resolve("cancel")), cancelled::get);
            outcome = "COMPLETED";
        } catch (InterruptedException interrupted) {
            outcome = "CANCELLED";
        }
        System.out.println(String.format("%-34s %s  %d ms", file.getFileName(), outcome,
                (System.nanoTime() - started) / 1_000_000));
        System.out.println("DESCENDANTS_AFTER " + ProcessHandle.current().descendants().count());
    }

    private static void rejects(Path ffmpeg, Path ffprobe, Path fixtures, Path out) throws Exception {
        var transcoder = new FfmpegMediaTranscoder(ffmpeg, Duration.ofMinutes(10), 2, BUDGET);
        try (Stream<Path> files = Files.list(fixtures)) {
            for (var file : files.filter(p -> p.getFileName().toString().startsWith("corrupt-tail-")).sorted().toList()) {
                var name = file.getFileName().toString();
                var metadata = metadata(ffprobe, file);
                if (metadata == null) { System.out.println(String.format("%-30s PROBE-REJECTED", name)); continue; }
                var plan = RenditionPlan.of(metadata);
                var workspace = Files.createDirectories(out.resolve(name));
                String outcome;
                try {
                    transcoder.transcode(file, plan, workspace, () -> false);
                    HlsPackage.seal(workspace, plan, metadata.durationMillis());
                    outcome = "SEALED";
                } catch (MediaTranscoder.Undecodable undecodable) {
                    outcome = "UNDECODABLE";
                } catch (MediaTranscoder.Unavailable | HlsPackage.Malformed failure) {
                    outcome = "RETRYABLE " + failure.getMessage();
                }
                System.out.println(String.format("%-30s PROBE-ACCEPTED %dms  %s  leftover=%s", name,
                        metadata.durationMillis(), outcome, inventory(workspace)));
            }
        }
        System.out.println("DESCENDANTS_AFTER " + ProcessHandle.current().descendants().count());
    }

    /** Signals the encoder from outside the runner once two segments exist: ffmpeg catches TERM, not KILL. */
    private static void signal(Path ffmpeg, Path ffprobe, Path file, Path out, String signal) throws Exception {
        var metadata = metadata(ffprobe, file);
        var plan = RenditionPlan.of(metadata);
        var transcoder = new FfmpegMediaTranscoder(ffmpeg, Duration.ofMinutes(10), 2, BUDGET);
        var workspace = Files.createDirectories(out.resolve("signal-" + signal));
        var outcome = new java.util.concurrent.atomic.AtomicReference<>("COMPLETED");
        var encoding = Thread.ofPlatform().start(() -> {
            try { transcoder.transcode(file, plan, workspace, () -> false); }
            catch (MediaTranscoder.Unavailable unavailable) { outcome.set("UNAVAILABLE"); }
            catch (MediaTranscoder.Undecodable undecodable) { outcome.set("UNDECODABLE"); }
            catch (Exception other) { outcome.set(other.getClass().getSimpleName()); }
        });
        while (!Files.exists(workspace.resolve("segment-00002.ts")) && encoding.isAlive()) { Thread.sleep(10); }
        // ProcessHandle.destroy is SIGTERM on Linux, destroyForcibly SIGKILL.
        ProcessHandle.current().descendants().filter(handle -> handle.info().command()
                .map(command -> command.equals(ffmpeg.toString())).orElse(false))
                .forEach(handle -> { if (signal.equals("TERM")) { handle.destroy(); } else { handle.destroyForcibly(); } });
        encoding.join();
        var playlist = workspace.resolve(HlsPackage.VARIANT);
        boolean ended = Files.exists(playlist) && Files.readString(playlist).contains("#EXT-X-ENDLIST");
        System.out.println(String.format("SIG%s %s  endlist=%s  seal=%s  leftover=%s", signal, outcome.get(), ended,
                sealVerdict(workspace, plan, metadata), inventory(workspace)));
        System.out.println("DESCENDANTS_AFTER " + ProcessHandle.current().descendants().count());
    }

    /** {@code out} sits on a file system far smaller than the rendition: the muxer runs out of space mid-segment. */
    private static void full(Path ffmpeg, Path ffprobe, Path file, Path out) throws Exception {
        var metadata = metadata(ffprobe, file);
        var plan = RenditionPlan.of(metadata);
        var transcoder = new FfmpegMediaTranscoder(ffmpeg, Duration.ofMinutes(10), 2, BUDGET);
        var workspace = Files.createDirectories(out.resolve("full"));
        String outcome;
        try {
            transcoder.transcode(file, plan, workspace, () -> false);
            outcome = "EXIT_ZERO";
        } catch (MediaTranscoder.Unavailable unavailable) {
            outcome = "UNAVAILABLE";
        } catch (MediaTranscoder.Undecodable undecodable) {
            outcome = "UNDECODABLE";
        }
        System.out.println(String.format("FULL %s  seal=%s  leftover=%s", outcome,
                sealVerdict(workspace, plan, metadata), inventory(workspace)));
        System.out.println("DESCENDANTS_AFTER " + ProcessHandle.current().descendants().count());
    }

    private static String sealVerdict(Path workspace, RenditionPlan plan, SourceMetadata metadata) {
        try {
            HlsPackage.seal(workspace, plan, metadata.durationMillis());
            return "SEALED";
        } catch (HlsPackage.Malformed malformed) {
            return "REJECTED " + malformed.getMessage();
        }
    }

    /** Names and sizes of what an attempt left behind; a segment that is not whole transport packets is marked. */
    private static String inventory(Path workspace) throws IOException {
        try (Stream<Path> files = Files.list(workspace)) {
            return files.sorted().map(path -> {
                try {
                    long size = Files.size(path);
                    boolean cut = path.getFileName().toString().endsWith(".ts") && size % 188 != 0;
                    return path.getFileName() + ":" + size + (cut ? "(partial-packet)" : "");
                } catch (IOException exception) {
                    return path.getFileName() + ":?";
                }
            }).toList().toString();
        }
    }

    private static String run(Path tool, String... arguments) throws Exception {
        var command = new java.util.ArrayList<String>(List.of(tool.toString()));
        command.addAll(List.of(arguments));
        var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        process.getOutputStream().close();
        var text = new String(process.getInputStream().readNBytes(1 << 16)).strip();
        if (!process.waitFor(300, TimeUnit.SECONDS)) { process.destroyForcibly(); }
        return text.replace('\n', ' ');
    }

    private static void accounting() throws Exception {
        for (var name : new String[] {"memory.peak", "pids.peak"}) {
            var file = Path.of("/sys/fs/cgroup", name);
            if (Files.isReadable(file)) { System.out.println("CGROUP " + name + " " + Files.readString(file).strip()); }
        }
        var cpu = Path.of("/sys/fs/cgroup/cpu.stat");
        if (Files.isReadable(cpu)) {
            for (var line : Files.readAllLines(cpu)) {
                if (line.startsWith("nr_throttled") || line.startsWith("throttled_usec")) {
                    System.out.println("CGROUP " + line);
                }
            }
        }
    }
}
