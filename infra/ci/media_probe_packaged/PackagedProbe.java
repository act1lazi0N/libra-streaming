package com.libra.streaming.media.processing.infrastructure;

import com.libra.streaming.media.processing.application.MediaProber;
import com.libra.streaming.media.processing.application.SourcePolicy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.stream.Stream;

/**
 * M20 evidence harness, run inside the packaged Media image (see docs/media-probe-hardening-m20.md). It lives in the
 * production package because the prober is package-private, and it is compiled against the classes unpacked from
 * the image's own jar, so the code under test is the shipped code and the tool is the image's ffprobe.
 *
 * <pre>
 * matrix  ffprobe directory timeoutSeconds maxOutputBytes   one verdict line per file
 * limits  ffprobe file timeoutMillis maxOutputBytes         one probe under a deliberately tiny budget
 * cancel  ffprobe file cancelAfterMillis                    one probe whose worker is cancelled while it runs
 * </pre>
 *
 * Every mode ends with the container's own accounting: peak memory, peak process count and CPU throttling.
 */
public final class PackagedProbe {
    private PackagedProbe() {}

    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "matrix" -> matrix(Path.of(args[1]), Path.of(args[2]), Long.parseLong(args[3]),
                    Integer.parseInt(args[4]));
            case "limits" -> limits(Path.of(args[1]), Path.of(args[2]), Long.parseLong(args[3]),
                    Integer.parseInt(args[4]));
            case "cancel" -> cancel(Path.of(args[1]), Path.of(args[2]), Long.parseLong(args[3]));
            default -> throw new IllegalArgumentException(args[0]);
        }
        accounting();
    }

    private static void matrix(Path tool, Path directory, long timeoutSeconds, int maxOutput) throws Exception {
        var prober = new FfprobeMediaProber(tool, Duration.ofSeconds(timeoutSeconds), maxOutput);
        System.out.println("TOOL " + prober.version());
        try (Stream<Path> files = Files.list(directory)) {
            for (var file : files.filter(Files::isRegularFile).filter(p -> !p.getFileName().toString().startsWith("."))
                    .sorted().toList()) {
                System.out.println(String.format("%-34s %s", file.getFileName(), one(prober, file)));
            }
        }
    }

    private static void limits(Path tool, Path file, long timeoutMillis, int maxOutput) throws Exception {
        var prober = new FfprobeMediaProber(tool, Duration.ofMillis(timeoutMillis), maxOutput);
        warmUp(tool);
        System.out.println(String.format("%-34s %s", file.getFileName(), one(prober, file)));
        // A terminated probe must leave nothing running under this process.
        System.out.println("DESCENDANTS_AFTER " + ProcessHandle.current().descendants().count());
    }

    private static void cancel(Path tool, Path file, long afterMillis) throws Exception {
        var prober = new FfprobeMediaProber(tool, Duration.ofSeconds(60), 262144);
        warmUp(tool);
        long started = System.nanoTime();
        var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        Thread.ofVirtual().start(() -> {
            try { Thread.sleep(afterMillis); } catch (InterruptedException ignored) { return; }
            cancelled.set(true);
        });
        String outcome;
        try {
            prober.probe(file, cancelled::get);
            outcome = "COMPLETED";
        } catch (InterruptedException interrupted) {
            outcome = "CANCELLED";
        }
        System.out.println(String.format("%-34s %s  %d ms", file.getFileName(), outcome,
                (System.nanoTime() - started) / 1_000_000));
        System.out.println("DESCENDANTS_AFTER " + ProcessHandle.current().descendants().count());
    }

    /**
     * Runs the tool once so the first process spawn is not part of what is timed, then signals the container's
     * freezer loop (if any) that real probes may be frozen from now on.
     */
    private static void warmUp(Path tool) throws Exception {
        new FfprobeMediaProber(tool, Duration.ofSeconds(20), 4096).version();
        Files.writeString(Path.of("/tmp/freeze"), "");
    }

    private static String one(FfprobeMediaProber prober, Path file) throws Exception {
        long started = System.nanoTime();
        String outcome;
        try {
            var verdict = SourcePolicy.evaluate(prober.probe(file, () -> false));
            outcome = verdict instanceof SourcePolicy.Verdict.Rejected rejected
                    ? "REJECTED " + rejected.failure() + " " + rejected.reason() : "ACCEPTED";
        } catch (MediaProber.Unreadable unreadable) {
            outcome = "UNREADABLE";
        } catch (MediaProber.Unavailable unavailable) {
            outcome = "UNAVAILABLE";
        }
        return outcome + "  " + (System.nanoTime() - started) / 1_000_000 + " ms";
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
