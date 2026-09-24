package com.libra.streaming.media.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import static com.libra.streaming.media.persistence.MediaPersistenceService.Snapshot;

@Repository
class MediaUploadStore {
    private final JdbcTemplate jdbc;

    MediaUploadStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    Snapshot find(UUID uploadId) {
        return jdbc.query(SELECT + " WHERE u.id = ?", (rs, row) -> map(rs), uploadId)
                .stream().findFirst().orElse(null);
    }

    Snapshot lock(UUID uploadId) {
        boolean found = !jdbc.query("SELECT id FROM media_uploads WHERE id = ? FOR UPDATE",
                (rs, row) -> rs.getObject("id", UUID.class), uploadId).isEmpty();
        return found ? find(uploadId) : null;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    void insertAsset(MediaPersistenceService.Ensure input, Instant now) {
        jdbc.update("""
                INSERT INTO media_assets(asset_id, asset_version, content_id, binding_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """, input.assetId(), input.assetVersion(), input.contentId(), input.bindingId(),
                Timestamp.from(now), Timestamp.from(now));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    void insertUpload(MediaPersistenceService.Ensure input, String fingerprint, Instant now) {
        jdbc.update("""
                INSERT INTO media_uploads(id, request_id, content_id, binding_id, asset_id, asset_version,
                    byte_length, expected_sha256, request_fingerprint, staging_key, created_at, updated_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """, input.uploadId(), input.requestId(), input.contentId(), input.bindingId(), input.assetId(),
                input.assetVersion(), input.byteLength(), input.sha256(), fingerprint,
                "staging/" + input.uploadId() + "/source.mp4", Timestamp.from(now), Timestamp.from(now),
                Timestamp.from(input.expiresAt()));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    UUID queue(UUID uploadId, UUID assetId, long assetVersion, UUID jobId, Instant now) {
        Snapshot current = lock(uploadId);
        if (current == null) { throw new MediaPersistenceException("NOT_FOUND"); }
        if (!current.assetId().equals(assetId) || current.assetVersion() != assetVersion) {
            throw new MediaPersistenceException("UPLOAD_STATE_CONFLICT");
        }
        if (current.uploadState().equals("SUBMITTED") && current.jobId() != null) { return current.jobId(); }
        if (!current.uploadState().equals("OPEN") || !current.assetState().equals("UPLOADING")
                || !current.expiresAt().isAfter(now)) {
            throw new MediaPersistenceException("UPLOAD_STATE_CONFLICT");
        }
        int changed = jdbc.update("UPDATE media_uploads SET state = 'SUBMITTED', updated_at = ? WHERE id = ? AND state = 'OPEN'",
                Timestamp.from(now), uploadId);
        changed += jdbc.update("""
                UPDATE media_assets SET state = 'QUEUED', updated_at = ?
                WHERE asset_id = ? AND asset_version = ? AND state = 'UPLOADING'
                """, Timestamp.from(now), assetId, assetVersion);
        if (changed != 2) { throw new MediaPersistenceException("UPLOAD_STATE_CONFLICT"); }
        jdbc.update("""
                INSERT INTO media_jobs(id, upload_id, next_attempt_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?)
                """, jobId, uploadId, Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
        return jobId;
    }

    private static Snapshot map(ResultSet rs) throws SQLException {
        return new Snapshot(rs.getObject("id", UUID.class), rs.getObject("request_id", UUID.class),
                rs.getObject("content_id", UUID.class), rs.getObject("binding_id", UUID.class),
                rs.getObject("asset_id", UUID.class), rs.getLong("asset_version"), rs.getLong("byte_length"),
                rs.getString("expected_sha256"), rs.getString("request_fingerprint"), rs.getString("upload_state"),
                rs.getString("asset_state"), rs.getObject("job_id", UUID.class),
                rs.getTimestamp("expires_at").toInstant());
    }

    private static final String SELECT = """
            SELECT u.*, u.state AS upload_state, a.state AS asset_state, j.id AS job_id
            FROM media_uploads u
            JOIN media_assets a ON a.asset_id = u.asset_id AND a.asset_version = u.asset_version
            LEFT JOIN media_jobs j ON j.upload_id = u.id
            """;

}
