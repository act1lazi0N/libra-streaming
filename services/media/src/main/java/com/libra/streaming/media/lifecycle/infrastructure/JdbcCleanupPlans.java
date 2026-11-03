package com.libra.streaming.media.lifecycle.infrastructure;

import com.libra.streaming.media.lifecycle.application.CleanupPlans;
import com.libra.streaming.media.lifecycle.domain.CleanupPlan;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 5)
public class JdbcCleanupPlans implements CleanupPlans {
    /**
     * Settled uploads only. An expired upload has no job and a submitted one a terminal job (both final, guarded by
     * V8), and READY and every selection are immutable (V5), so nothing a plan names to delete can become selected.
     * The settlement is the later of the upload deadline (no grant outlives it) and the terminal transition (no lease
     * outlives it); the grace is measured from there, never from an object's age.
     */
    static final String SETTLED = """
            u.objects_cleaned_at IS NULL AND (
                (u.state = 'EXPIRED' AND j.id IS NULL AND a.state = 'FAILED'
                    AND u.expires_at <= clock_timestamp() - make_interval(secs => ?))
                OR (u.state = 'SUBMITTED' AND j.stage IN ('SUCCEEDED', 'FAILED_PERMANENT', 'EXHAUSTED')
                    AND GREATEST(u.expires_at, j.updated_at) <= clock_timestamp() - make_interval(secs => ?)))
            """;

    private final JdbcTemplate jdbc;

    public JdbcCleanupPlans(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public List<CleanupPlan> due(Duration writeGrace, int limit) {
        if (writeGrace == null || writeGrace.isNegative() || limit < 1 || limit > 500) {
            throw new IllegalArgumentException("Invalid cleanup selection");
        }
        double seconds = writeGrace.toMillis() / 1000.0;
        var plans = jdbc.query("""
                SELECT u.id, u.staging_key, coalesce(j.attempt_count, 0), a.selected_source_key, a.output_prefix
                FROM media_uploads u
                    JOIN media_assets a ON a.asset_id = u.asset_id AND a.asset_version = u.asset_version
                    LEFT JOIN media_jobs j ON j.upload_id = u.id
                """ + " WHERE " + SETTLED + """
                ORDER BY u.cleanup_attempted_at NULLS FIRST, u.expires_at, u.id
                LIMIT ? FOR UPDATE OF u SKIP LOCKED
                """, (rs, row) -> new CleanupPlan(rs.getObject(1, UUID.class), rs.getString(2), rs.getInt(3),
                rs.getString(4), rs.getString(5)), seconds, seconds, limit);
        // Taking a batch moves it to the back of the queue, whatever this pass then manages to delete.
        for (var plan : plans) {
            jdbc.update("UPDATE media_uploads SET cleanup_attempted_at = clock_timestamp() WHERE id = ?",
                    plan.uploadId());
        }
        return plans;
    }

    @Override
    public boolean markCleaned(UUID uploadId) {
        // V8 refuses the mark for an upload whose objects could still be selected.
        return jdbc.update("""
                UPDATE media_uploads SET objects_cleaned_at = clock_timestamp()
                WHERE id = ? AND objects_cleaned_at IS NULL
                """, uploadId) == 1;
    }
}
