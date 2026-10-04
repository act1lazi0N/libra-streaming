package com.libra.streaming.media.processing.infrastructure;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Finds the real ffprobe for tests: LIBRA_FFPROBE_PATH, else the PATH. A missing tool fails the build, like a
 * missing Docker daemon, instead of silently skipping the evidence.
 */
final class FfprobeLocator {
    private FfprobeLocator() {}

    static Path locate() {
        var configured = System.getenv("LIBRA_FFPROBE_PATH");
        if (configured != null && !configured.isBlank()) { return Path.of(configured).toAbsolutePath(); }
        var names = System.getProperty("os.name").toLowerCase().contains("win")
                ? List.of("ffprobe.exe") : List.of("ffprobe");
        var path = System.getenv("PATH");
        for (String directory : (path == null ? "" : path).split(java.io.File.pathSeparator)) {
            if (directory.isBlank()) { continue; }
            for (String name : names) {
                var candidate = Path.of(directory).resolve(name);
                if (Files.isRegularFile(candidate)) { return candidate.toAbsolutePath(); }
            }
        }
        throw new AssertionError("ffprobe not found: set LIBRA_FFPROBE_PATH to an absolute ffprobe executable");
    }
}
