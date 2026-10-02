package com.libra.streaming.media.processing.infrastructure;

import com.libra.streaming.media.processing.application.JobLeases;
import com.libra.streaming.media.processing.domain.JobLease;
import com.libra.streaming.media.processing.domain.ProcessingFailure;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
// Bound lock/query waits so an unavailable renewal cannot retain a row lock indefinitely.
@Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 5)
public class JdbcJobLeases implements JobLeases {
    private final JdbcTemplate jdbc;

    public JdbcJobLeases(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public Optional<JobLease> claim(Duration lifetime) {
        timing(lifetime, Duration.ofMinutes(5));
        var candidates = jdbc.query("""
                SELECT j.id, j.upload_id, j.attempt_count, u.asset_id, u.asset_version
                FROM media_jobs j JOIN media_uploads u ON u.id = j.upload_id
                WHERE (j.stage = 'QUEUED' AND j.next_attempt_at <= clock_timestamp())
                   OR (j.stage IN ('CLAIMED', 'SOURCE_SELECTED', 'TRANSCODING', 'FINALIZING')
                       AND j.lease_until <= clock_timestamp())
                ORDER BY j.next_attempt_at, j.created_at, j.id
                LIMIT 1 FOR UPDATE OF j SKIP LOCKED
                """, (rs, row) -> new Candidate(rs.getObject("id", UUID.class), rs.getObject("upload_id", UUID.class),
                rs.getObject("asset_id", UUID.class), rs.getLong("asset_version"), rs.getInt("attempt_count")));
        if (candidates.isEmpty()) { return Optional.empty(); }
        var current = candidates.getFirst();
        Instant now = now();
        if (current.attempt() == 3) {
            terminal(current, "EXHAUSTED", "RETRY_EXHAUSTED", now);
            return Optional.empty();
        }
        var token = UUID.randomUUID();
        Instant expiry = now.plus(lifetime);
        jdbc.update("""
                UPDATE media_jobs SET stage = 'CLAIMED', attempt_count = attempt_count + 1,
                    lease_token = ?, lease_until = ?, failure_code = NULL, updated_at = ? WHERE id = ?
                """, token, Timestamp.from(expiry), Timestamp.from(now), current.jobId());
        int changed = jdbc.update("""
                UPDATE media_assets SET state = 'PROCESSING', updated_at = ?
                WHERE asset_id = ? AND asset_version = ? AND state IN ('QUEUED', 'PROCESSING')
                """, Timestamp.from(now), current.assetId(), current.assetVersion());
        if (changed != 1) { throw new IllegalStateException("Invalid job asset state"); }
        return Optional.of(new JobLease(current.jobId(), current.uploadId(), current.assetId(), current.assetVersion(),
                current.attempt() + 1, token, expiry));
    }

    @Override
    public boolean renew(JobLease lease, Duration lifetime) {
        timing(lifetime, Duration.ofMinutes(5));
        Instant now = lockCurrent(lease);
        if (now == null) { return false; }
        return jdbc.update("UPDATE media_jobs SET lease_until = ?, updated_at = ? WHERE id = ?",
                Timestamp.from(now.plus(lifetime)), Timestamp.from(now), lease.jobId()) == 1;
    }

    @Override
    public boolean retry(JobLease lease, ProcessingFailure failure, Duration delay) {
        timing(delay, Duration.ofHours(1));
        return finish(lease, failure, delay, !failure.retryable());
    }

    @Override
    public boolean fail(JobLease lease, ProcessingFailure failure) {
        return finish(lease, failure, Duration.ZERO, true);
    }

    @Override
    public boolean release(JobLease lease) {
        Instant now = lockCurrent(lease);
        if (now == null) { return false; }
        if (lease.attempt() == 3) {
            terminal(candidate(lease), "EXHAUSTED", "RETRY_EXHAUSTED", now);
        } else {
            requeue(lease, now, now, null);
        }
        return true;
    }

    private boolean finish(JobLease lease, ProcessingFailure failure, Duration delay, boolean permanent) {
        java.util.Objects.requireNonNull(failure);
        Instant now = lockCurrent(lease);
        if (now == null) { return false; }
        if (permanent || lease.attempt() == 3) {
            terminal(candidate(lease), permanent ? "FAILED_PERMANENT" : "EXHAUSTED",
                    permanent ? failure.name() : "RETRY_EXHAUSTED", now);
        } else {
            requeue(lease, now, now.plus(delay), failure.name());
        }
        return true;
    }

    private Instant lockCurrent(JobLease lease) { return LeaseFence.lockCurrent(jdbc, lease); }

    private void requeue(JobLease lease, Instant now, Instant due, String reason) {
        jdbc.update("""
                UPDATE media_jobs SET stage = 'QUEUED', lease_token = NULL, lease_until = NULL,
                    next_attempt_at = ?, failure_code = ?, updated_at = ? WHERE id = ?
                """, Timestamp.from(due), reason, Timestamp.from(now), lease.jobId());
        // A retry remains PROCESSING to avoid exposing an editorial state regression.
    }

    private void terminal(Candidate current, String stage, String reason, Instant now) {
        jdbc.update("""
                UPDATE media_jobs SET stage = ?, lease_token = NULL, lease_until = NULL,
                    failure_code = ?, updated_at = ? WHERE id = ?
                """, stage, reason, Timestamp.from(now), current.jobId());
        int changed = jdbc.update("""
                UPDATE media_assets SET state = 'FAILED', updated_at = ?
                WHERE asset_id = ? AND asset_version = ? AND state IN ('QUEUED', 'PROCESSING')
                """, Timestamp.from(now), current.assetId(), current.assetVersion());
        if (changed != 1) { throw new IllegalStateException("Invalid job asset state"); }
    }

    private Instant now() { return jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant(); }
    private static Candidate candidate(JobLease lease) {
        return new Candidate(lease.jobId(), lease.uploadId(), lease.assetId(), lease.assetVersion(), lease.attempt());
    }
    private static void timing(Duration value, Duration max) {
        if (value == null || value.toMillis() < 1 || value.compareTo(max) > 0) {
            throw new IllegalArgumentException("Invalid job timing");
        }
    }
    private record Candidate(UUID jobId, UUID uploadId, UUID assetId, long assetVersion, int attempt) {}
}
