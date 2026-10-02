package com.libra.streaming.media.processing.infrastructure;

import com.libra.streaming.media.processing.application.JobSources;
import com.libra.streaming.media.processing.domain.JobLease;
import java.sql.Timestamp;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
// Same bounded transactions as the lease adapter: no storage or process I/O ever runs inside them.
@Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 5)
public class JdbcJobSources implements JobSources {
    private final JdbcTemplate jdbc;

    public JdbcJobSources(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public Optional<Target> target(JobLease lease) {
        if (lease == null) { return Optional.empty(); }
        return jdbc.query("""
                SELECT u.staging_key, u.byte_length, u.expected_sha256, a.selected_source_key
                FROM media_jobs j JOIN media_uploads u ON u.id = j.upload_id
                    JOIN media_assets a ON a.asset_id = u.asset_id AND a.asset_version = u.asset_version
                """ + " WHERE " + LeaseFence.CURRENT + " AND j.lease_until > clock_timestamp()",
                (rs, row) -> new Target(rs.getString(1), rs.getLong(2), rs.getString(3), rs.getString(4)),
                LeaseFence.arguments(lease)).stream().findFirst();
    }

    @Override
    public boolean select(JobLease lease, String sourceKey) {
        if (sourceKey == null || sourceKey.isBlank() || sourceKey.length() > 512) {
            throw new IllegalArgumentException("Invalid source key");
        }
        var now = LeaseFence.lockCurrent(jdbc, lease);
        if (now == null) { return false; }
        // Selection is not an editorial change: the aggregate version and outbox are untouched.
        int selected = jdbc.update("""
                UPDATE media_assets SET selected_source_key = ?, updated_at = ?
                WHERE asset_id = ? AND asset_version = ? AND state = 'PROCESSING' AND selected_source_key IS NULL
                """, sourceKey, Timestamp.from(now), lease.assetId(), lease.assetVersion());
        if (selected == 0 && !sourceKey.equals(selectedKey(lease))) {
            throw new IllegalStateException("Selected source is immutable");
        }
        jdbc.update("""
                UPDATE media_jobs SET stage = 'SOURCE_SELECTED', updated_at = ?
                WHERE id = ? AND stage IN ('CLAIMED', 'SOURCE_SELECTED')
                """, Timestamp.from(now), lease.jobId());
        return true;
    }

    private String selectedKey(JobLease lease) {
        return jdbc.query("SELECT selected_source_key FROM media_assets WHERE asset_id = ? AND asset_version = ?",
                (rs, row) -> rs.getString(1), lease.assetId(), lease.assetVersion()).stream().findFirst().orElse(null);
    }
}
