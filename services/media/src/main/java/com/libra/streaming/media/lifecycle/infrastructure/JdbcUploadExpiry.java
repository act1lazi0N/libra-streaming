package com.libra.streaming.media.lifecycle.infrastructure;

import com.libra.streaming.media.lifecycle.application.UploadExpiry;
import com.libra.streaming.media.processing.infrastructure.MediaAssetEvents;
import java.sql.Timestamp;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
// Short and bounded like every other Media transition; no storage I/O runs inside it.
@Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 5)
public class JdbcUploadExpiry implements UploadExpiry {
    private final JdbcTemplate jdbc;

    public JdbcUploadExpiry(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public int expire(int limit) {
        if (limit < 1 || limit > 500) { throw new IllegalArgumentException("Invalid expiry batch"); }
        // Completion locks the same row and checks the deadline after the lock, so an upload is either submitted or
        // expired, never both. The deadline is the database's clock, as for every other lease and deadline here.
        var due = jdbc.query("""
                SELECT u.id, u.asset_id, u.asset_version FROM media_uploads u
                    JOIN media_assets a ON a.asset_id = u.asset_id AND a.asset_version = u.asset_version
                WHERE u.state = 'OPEN' AND a.state = 'UPLOADING' AND u.expires_at <= clock_timestamp()
                ORDER BY u.expires_at, u.id
                LIMIT ? FOR UPDATE OF u, a SKIP LOCKED
                """, (rs, row) -> new Due(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getLong(3)),
                limit);
        if (due.isEmpty()) { return 0; }
        var now = jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class);
        for (var upload : due) {
            int changed = jdbc.update("""
                    UPDATE media_uploads SET state = 'EXPIRED', updated_at = ? WHERE id = ? AND state = 'OPEN'
                    """, now, upload.uploadId());
            changed += jdbc.update("""
                    UPDATE media_assets SET state = 'FAILED', updated_at = ?
                    WHERE asset_id = ? AND asset_version = ? AND state = 'UPLOADING'
                    """, now, upload.assetId(), upload.assetVersion());
            if (changed != 2) { throw new IllegalStateException("Upload changed while locked"); }
            MediaAssetEvents.append(jdbc, upload.assetId(), upload.assetVersion(), MediaAssetEvents.State.FAILED,
                    null, now.toInstant());
        }
        return due.size();
    }

    private record Due(UUID uploadId, UUID assetId, long assetVersion) {}
}
