package com.libra.streaming.media.processing.infrastructure;

import com.libra.streaming.media.processing.application.OutputSelections;
import com.libra.streaming.media.processing.domain.JobLease;
import java.sql.Timestamp;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
// Same bounded, fenced transactions as the lease and selection adapters; no storage I/O ever runs inside one.
@Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 5)
public class JdbcOutputSelections implements OutputSelections {
    private final JdbcTemplate jdbc;

    public JdbcOutputSelections(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public boolean beginFinalizing(JobLease lease) {
        var now = LeaseFence.lockCurrent(jdbc, lease);
        if (now == null) { return false; }
        return jdbc.update("""
                UPDATE media_jobs SET stage = 'FINALIZING', updated_at = ?
                WHERE id = ? AND stage IN ('TRANSCODING', 'FINALIZING')
                """, Timestamp.from(now), lease.jobId()) == 1;
    }

    @Override
    public boolean select(JobLease lease, Selection selection) {
        Objects.requireNonNull(selection);
        var now = LeaseFence.lockCurrent(jdbc, lease);
        if (now == null) { return false; }
        // The output is chosen once, for the source that was encoded; anything else rolls the whole commit back.
        int ready = jdbc.update("""
                UPDATE media_assets SET state = 'READY', output_prefix = ?, master_manifest_key = ?,
                    duration_seconds = ?, updated_at = ?
                WHERE asset_id = ? AND asset_version = ? AND state = 'PROCESSING'
                    AND output_prefix IS NULL AND selected_source_key = ?
                """, selection.outputPrefix(), selection.masterKey(), selection.durationSeconds(), Timestamp.from(now),
                lease.assetId(), lease.assetVersion(), selection.sourceKey());
        if (ready != 1) { throw new IllegalStateException("Selected output is immutable"); }
        MediaAssetEvents.append(jdbc, lease.assetId(), lease.assetVersion(), MediaAssetEvents.State.READY,
                selection.durationSeconds(), now);
        int succeeded = jdbc.update("""
                UPDATE media_jobs SET stage = 'SUCCEEDED', lease_token = NULL, lease_until = NULL,
                    failure_code = NULL, updated_at = ?
                WHERE id = ? AND stage = 'FINALIZING'
                """, Timestamp.from(now), lease.jobId());
        if (succeeded != 1) { throw new IllegalStateException("Output selected outside finalizing"); }
        return true;
    }
}
