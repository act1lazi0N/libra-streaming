package com.libra.streaming.media.processing.application;

import com.libra.streaming.media.processing.domain.JobLease;
import com.libra.streaming.media.processing.domain.SourceMetadata;
import java.util.Optional;

/** Validated metadata of the selected source, written only under the current lease. */
public interface SourceMetadataStore {
    /** Metadata recorded for the selected source; empty if none, or if the lease is no longer current. */
    Optional<SourceMetadata> find(JobLease lease);

    /**
     * Records the metadata of {@code sourceKey}, which must be the asset's selected source. Recording the same
     * values again is idempotent; different values are a bug. False means the lease lost authority.
     */
    boolean record(JobLease lease, String sourceKey, SourceMetadata metadata);
}
