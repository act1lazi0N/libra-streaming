package com.libra.streaming.media.storage;

import com.libra.streaming.media.processing.application.TranscodeWorkspaces;
import com.libra.streaming.media.processing.domain.JobLease;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Encoder workspaces carved out of the same locked scratch root as source copies, so the startup reclaim that
 * removes a dead instance's source files removes its workspaces too. Each belongs to one claim.
 */
@Component
public class S3TranscodeWorkspaces implements TranscodeWorkspaces {
    private final ObjectProvider<S3MediaStorage> storage;

    public S3TranscodeWorkspaces(ObjectProvider<S3MediaStorage> storage) { this.storage = storage; }

    @Override
    public Workspace open(JobLease lease, long budgetBytes) {
        var available = storage.getIfAvailable();
        if (available == null) { throw new Unavailable(); }
        try {
            return new Directory(available.scratchDirectory("hls-attempt-" + lease.attempt() + "-", budgetBytes));
        } catch (IOException exception) {
            throw new Unavailable();
        }
    }

    private static final class Directory implements Workspace {
        private final Path directory;

        Directory(Path directory) { this.directory = directory; }

        @Override
        public Path directory() { return directory; }

        @Override
        public void close() {
            // Windows can refuse to delete a file for a moment after the process that wrote it was killed.
            for (int attempt = 0; attempt < 4; attempt++) {
                try {
                    deleteTree(directory);
                    return;
                } catch (IOException busy) {
                    try { Thread.sleep(25L << attempt); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
                }
            }
            // What is left is reclaimed with the scratch directory of this instance.
        }

        private static void deleteTree(Path path) throws IOException {
            if (!Files.exists(path)) { return; }
            try (Stream<Path> tree = Files.walk(path)) {
                for (Path entry : tree.sorted(Comparator.reverseOrder()).toList()) { Files.deleteIfExists(entry); }
            }
        }
    }
}
