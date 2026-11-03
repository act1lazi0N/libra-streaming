package com.libra.streaming.media.processing.application;

import com.libra.streaming.media.processing.domain.JobLease;
import java.util.Optional;

/** Source selection is fenced by the current lease; each operation owns a short transaction. */
public interface JobSources {
    /** Empty when the lease is no longer the current, unexpired owner of its job. */
    Optional<Target> target(JobLease lease);

    /**
     * Commits the one immutable source for the asset. True means this exact key is now (or already was)
     * selected; false means the lease lost authority and nothing changed. A different selected key is a bug.
     */
    boolean select(JobLease lease, String sourceKey);

    record Target(String stagingKey, long byteLength, String sha256, String selectedKey) {}
}
