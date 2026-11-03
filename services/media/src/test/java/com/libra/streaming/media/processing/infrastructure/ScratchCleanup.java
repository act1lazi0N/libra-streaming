package com.libra.streaming.media.processing.infrastructure;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Deletes a test's scratch tree, retrying for a moment. Windows can keep a file open for a short time after the
 * process that read it was killed (the tests kill real ffprobe and ffmpeg on purpose), so a plain recursive delete
 * races the operating system. A tree that is still locked after the retries is a real leak and fails the test.
 */
final class ScratchCleanup {
    private ScratchCleanup() {}

    static void deleteTree(Path root) throws IOException, InterruptedException {
        IOException last = null;
        for (int attempt = 0; attempt < 20; attempt++) {
            try {
                if (!Files.exists(root)) { return; }
                try (Stream<Path> tree = Files.walk(root)) {
                    for (Path entry : tree.sorted(Comparator.reverseOrder()).toList()) { Files.deleteIfExists(entry); }
                }
                return;
            } catch (IOException busy) {
                last = busy;
                Thread.sleep(100);
            }
        }
        throw last;
    }
}
