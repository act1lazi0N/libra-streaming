package com.libra.streaming.media.processing.application;

import com.libra.streaming.media.processing.domain.JobLease;
import java.nio.file.Path;

/** Private, attempt-specific scratch directories for encoder output. Closing a workspace deletes everything in it. */
public interface TranscodeWorkspaces {
    /** An empty directory reserved for exactly one claim; fails if the scratch disk cannot hold {@code budgetBytes}. */
    Workspace open(JobLease lease, long budgetBytes);

    interface Workspace extends AutoCloseable {
        Path directory();

        @Override
        void close();
    }

    /** Transient scratch-disk failure; deliberately carries no path or system message. */
    final class Unavailable extends RuntimeException {
        public Unavailable() { super("TRANSCODE_WORKSPACE_UNAVAILABLE", null, false, false); }
    }
}
