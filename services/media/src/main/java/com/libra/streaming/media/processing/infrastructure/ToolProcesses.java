package com.libra.streaming.media.processing.infrastructure;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** What every trusted media tool run shares: an empty environment, no stdin and a guaranteed process-tree kill. */
final class ToolProcesses {
    static final Duration TERMINATION_GRACE = Duration.ofSeconds(5);

    private ToolProcesses() {}

    /** stderr is discarded (it can carry paths and parser messages); the caller decides what to do with stdout. */
    static ProcessBuilder builder(List<String> command) {
        var builder = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD);
        // The tool needs nothing from the environment; Windows loads its DLLs through SystemRoot.
        var inherited = System.getenv("SystemRoot");
        builder.environment().clear();
        if (inherited != null) { builder.environment().put("SystemRoot", inherited); }
        return builder;
    }

    /** Kills the process and everything it started, then waits a bounded time for them to be gone. */
    static void terminate(Process process) throws InterruptedException {
        if (!process.isAlive() && process.descendants().findAny().isEmpty()) { return; }
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        process.waitFor(TERMINATION_GRACE.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** First line of {@code <tool> -version}, for the runtime log and the evidence record. */
    static String version(java.nio.file.Path executable) throws InterruptedException {
        Process process;
        try {
            process = builder(List.of(executable.toString(), "-version")).start();
        } catch (IOException exception) {
            return "unavailable";
        }
        try {
            process.getOutputStream().close();
            byte[] head = new byte[512];
            int read = 0;
            try (var output = process.getInputStream()) {
                int count;
                while (read < head.length && (count = output.read(head, read, head.length - read)) > 0) { read += count; }
            }
            var text = new String(head, 0, read, StandardCharsets.UTF_8);
            int end = text.indexOf('\n');
            var line = (end < 0 ? text : text.substring(0, end)).strip();
            return line.length() > 120 ? line.substring(0, 120) : line;
        } catch (IOException exception) {
            return "unavailable";
        } finally {
            terminate(process);
        }
    }
}
