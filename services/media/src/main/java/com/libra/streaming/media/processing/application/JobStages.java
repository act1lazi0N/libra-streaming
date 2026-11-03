package com.libra.streaming.media.processing.application;

import com.libra.streaming.media.processing.domain.JobLease;

/** Visible job stage, advanced only by the current lease owner in one short transaction. */
public interface JobStages {
    /**
     * Moves a job whose source is selected to TRANSCODING. Repeating it is harmless. False means the lease lost
     * authority (or no source is selected) and nothing changed.
     */
    boolean beginTranscoding(JobLease lease);
}
