package com.libra.streaming.media.processing.infrastructure;

import com.libra.streaming.media.processing.application.JobStages;
import com.libra.streaming.media.processing.domain.JobLease;
import java.sql.Timestamp;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
// Same bounded, fenced transactions as the lease and selection adapters; no encoder runs inside one.
@Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 5)
public class JdbcJobStages implements JobStages {
    private final JdbcTemplate jdbc;

    public JdbcJobStages(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public boolean beginTranscoding(JobLease lease) {
        var now = LeaseFence.lockCurrent(jdbc, lease);
        if (now == null) { return false; }
        // Only a job with a committed source may encode; CLAIMED or a later stage is left untouched.
        return jdbc.update("""
                UPDATE media_jobs SET stage = 'TRANSCODING', updated_at = ?
                WHERE id = ? AND stage IN ('SOURCE_SELECTED', 'TRANSCODING')
                """, Timestamp.from(now), lease.jobId()) == 1;
    }
}
