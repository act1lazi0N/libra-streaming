package com.libra.streaming.media.processing.infrastructure;

import com.libra.streaming.media.processing.application.MediaProber;
import com.libra.streaming.media.processing.application.ProbeReport;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * Runs one trusted ffprobe executable with a fixed, structured argument list. The input is only ever a local file
 * addressed with the {@code file:} protocol, the protocol whitelist stops a container from referencing anything
 * else, and stdout is bounded. Process output, stderr and paths never leave this class: failures are reported
 * only as {@link MediaProber.Unavailable} (retry) or {@link MediaProber.Unreadable} (permanent).
 */
final class FfprobeMediaProber implements MediaProber {
    private static final Duration TERMINATION_GRACE = Duration.ofSeconds(5);
    private final Path executable;
    private final Duration timeout;
    private final int maxOutputBytes;

    FfprobeMediaProber(Path executable, Duration timeout, int maxOutputBytes) {
        if (!executable.isAbsolute()) { throw new IllegalArgumentException("ffprobe path must be absolute"); }
        if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("ffprobe timeout must be up to 5 minutes");
        }
        if (maxOutputBytes < 1024 || maxOutputBytes > 4 * 1024 * 1024) {
            throw new IllegalArgumentException("ffprobe output limit must be 1 KiB to 4 MiB");
        }
        this.executable = executable;
        this.timeout = timeout;
        this.maxOutputBytes = maxOutputBytes;
    }

    @Override
    public ProbeReport probe(Path file, BooleanSupplier cancelled) throws InterruptedException {
        Path real;
        try {
            real = file.toRealPath();
            if (!Files.isRegularFile(real)) { throw new Unavailable(); }
        } catch (IOException exception) {
            throw new Unavailable();
        }
        // "file:" pins the protocol, so a name that looks like a URL or option can never change the meaning.
        var command = List.of(executable.toString(), "-v", "error", "-hide_banner", "-protocol_whitelist", "file",
                "-print_format", "json", "-show_format", "-show_streams", "-i", "file:" + real);
        return FfprobeJson.parse(run(command, cancelled));
    }

    /** First line of {@code ffprobe -version}, for the runtime log and the evidence record. */
    String version() throws InterruptedException {
        var text = new String(run(List.of(executable.toString(), "-version"), () -> false),
                StandardCharsets.UTF_8);
        int end = text.indexOf('\n');
        var line = (end < 0 ? text : text.substring(0, end)).strip();
        return line.length() > 120 ? line.substring(0, 120) : line;
    }

    private byte[] run(List<String> command, BooleanSupplier cancelled) throws InterruptedException {
        var builder = new ProcessBuilder(command)
                .redirectError(ProcessBuilder.Redirect.DISCARD);
        // The tool needs nothing from the environment; Windows loads its DLLs through SystemRoot.
        var inherited = System.getenv("SystemRoot");
        builder.environment().clear();
        if (inherited != null) { builder.environment().put("SystemRoot", inherited); }
        Process process;
        try {
            process = builder.start();
        } catch (IOException exception) {
            throw new Unavailable();
        }
        var output = new BoundedOutput(maxOutputBytes);
        Thread reader = Thread.ofVirtual().start(() -> output.drain(process.getInputStream()));
        try {
            process.getOutputStream().close();
            long deadline = System.nanoTime() + timeout.toNanos();
            while (!process.waitFor(50, TimeUnit.MILLISECONDS)) {
                if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException();
                }
                if (output.overflowed()) { throw new Unreadable(); }
                if (System.nanoTime() - deadline > 0) { throw new Unavailable(); }
            }
            reader.join(TERMINATION_GRACE);
            // A reader still blocked means something other than the exited tool holds its pipe open.
            if (reader.isAlive()) { throw new Unavailable(); }
            if (output.overflowed() || process.exitValue() != 0) { throw new Unreadable(); }
            return output.bytes();
        } catch (IOException exception) {
            throw new Unavailable();
        } finally {
            terminate(process);
            reader.interrupt();
        }
    }

    private static void terminate(Process process) throws InterruptedException {
        if (!process.isAlive() && process.descendants().findAny().isEmpty()) { return; }
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        process.waitFor(TERMINATION_GRACE.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** Collects at most {@code limit} bytes; one byte beyond it marks the output as excessive and stops reading. */
    private static final class BoundedOutput {
        private final int limit;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private final AtomicBoolean overflow = new AtomicBoolean();

        BoundedOutput(int limit) { this.limit = limit; }

        void drain(InputStream stream) {
            byte[] chunk = new byte[8192];
            try {
                while (true) {
                    int read = stream.read(chunk);
                    if (read < 0) { return; }
                    synchronized (buffer) {
                        if (buffer.size() + read > limit) { overflow.set(true); return; }
                        buffer.write(chunk, 0, read);
                    }
                }
            } catch (IOException ignored) {
                // The pipe closed under us (process killed); the caller decides from the exit state.
            }
        }

        boolean overflowed() { return overflow.get(); }

        byte[] bytes() {
            synchronized (buffer) { return buffer.toByteArray(); }
        }
    }
}
