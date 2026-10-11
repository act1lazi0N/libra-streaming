package com.libra.streaming.media.processing.application;

import com.libra.streaming.media.processing.domain.JobLease;
import com.libra.streaming.media.processing.domain.SourceMetadata;

/** Output selection and the FINALIZING stage, fenced by the current lease; each call is one short transaction. */
public interface OutputSelections {
    /**
     * Moves a transcoding job to FINALIZING. Repeating it is harmless. False means the lease lost authority (or the
     * job never reached TRANSCODING) and nothing changed.
     */
    boolean beginFinalizing(JobLease lease);

    /**
     * Commits, in one transaction, the verified output generation, the validated duration, the READY transition, the
     * next aggregate version, its READY outbox event and the job's success. True means all of it committed; false means
     * the lease lost authority and nothing changed. An asset that already has an output, or whose selected source is
     * not the one encoded, is a bug and throws (a runtime exception) without committing anything.
     */
    boolean select(JobLease lease, Selection selection);

    /** What READY points at. {@code masterKey} lies directly under {@code outputPrefix}. */
    record Selection(String sourceKey, String outputPrefix, String masterKey, int durationSeconds) {
        public Selection {
            if (sourceKey == null || sourceKey.isBlank() || outputPrefix == null || outputPrefix.isBlank()
                    || outputPrefix.endsWith("/") || masterKey == null || !masterKey.startsWith(outputPrefix + "/")
                    || masterKey.indexOf('/', outputPrefix.length() + 1) >= 0
                    || durationSeconds < 1 || durationSeconds > SourceMetadata.MAX_DURATION_MILLIS / 1000) {
                throw new IllegalArgumentException("Invalid output selection");
            }
        }
    }
}
