package com.libra.streaming.media.processing.infrastructure;

import com.libra.streaming.media.processing.domain.JobLease;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;

/** The single definition of "current lease owner"; every fenced write shares it. */
final class LeaseFence {
    /** Binds job, upload, attempt, token, asset version and an active stage; callers add the expiry rule. */
    static final String CURRENT = """
            j.id = ? AND j.upload_id = ? AND j.attempt_count = ? AND j.lease_token = ?
                AND u.asset_id = ? AND u.asset_version = ?
                AND j.stage IN ('CLAIMED', 'SOURCE_SELECTED', 'TRANSCODING', 'FINALIZING')
            """;

    private LeaseFence() {}

    static Object[] arguments(JobLease lease) {
        return new Object[] {lease.jobId(), lease.uploadId(), lease.attempt(), lease.token(),
                lease.assetId(), lease.assetVersion()};
    }

    /**
     * Locks the job row and returns database time if the lease is still current. Expiry is judged after
     * the lock wait, so a renewal that queued behind another writer cannot revive an expired lease.
     */
    static Instant lockCurrent(JdbcTemplate jdbc, JobLease lease) {
        if (lease == null) { return null; }
        var expiry = jdbc.query("""
                SELECT j.lease_until FROM media_jobs j JOIN media_uploads u ON u.id = j.upload_id
                """ + " WHERE " + CURRENT + " FOR UPDATE OF j", (rs, row) -> rs.getTimestamp(1).toInstant(),
                arguments(lease));
        if (expiry.isEmpty()) { return null; }
        Instant now = jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant();
        return expiry.getFirst().isAfter(now) ? now : null;
    }
}
