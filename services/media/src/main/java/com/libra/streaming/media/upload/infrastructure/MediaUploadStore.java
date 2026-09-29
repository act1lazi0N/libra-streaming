package com.libra.streaming.media.upload.infrastructure;

import com.libra.streaming.media.upload.application.UploadFailure;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.Clock;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import static com.libra.streaming.media.upload.infrastructure.MediaPersistenceService.Snapshot;

@Repository
class MediaUploadStore {
    private final JdbcTemplate jdbc;
    private final String stagingPrefix;
    private final Clock clock;

    MediaUploadStore(JdbcTemplate jdbc, @Value("${libra.media.storage.staging-prefix}") String stagingPrefix,
            Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
        if (stagingPrefix == null || !stagingPrefix.matches("[a-z0-9][a-z0-9/_-]*/")
                || stagingPrefix.contains("//")) { throw new IllegalArgumentException("Invalid staging prefix"); }
        this.stagingPrefix = stagingPrefix;
    }

    Snapshot find(UUID uploadId) {
        return jdbc.query(SELECT + " WHERE u.id = ?", (rs, row) -> map(rs), uploadId)
                .stream().findFirst().orElse(null);
    }

    String stagingKey(UUID uploadId) {
        return jdbc.query("SELECT staging_key FROM media_uploads WHERE id = ?",
                (rs, row) -> rs.getString(1), uploadId).stream().findFirst()
                .orElseThrow(() -> new UploadFailure("NOT_FOUND"));
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
                stagingPrefix + input.uploadId() + "/source.mp4", Timestamp.from(now), Timestamp.from(now),
                Timestamp.from(input.expiresAt()));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    UUID queue(UUID uploadId, UUID assetId, long assetVersion, UUID jobId) {
        Snapshot current = lock(uploadId);
        if (current == null) { throw new UploadFailure("NOT_FOUND"); }
        if (!current.assetId().equals(assetId) || current.assetVersion() != assetVersion) {
            throw new UploadFailure("UPLOAD_STATE_CONFLICT");
        }
        if (current.uploadState().equals("SUBMITTED") && current.jobId() != null) { return current.jobId(); }
        if (!current.uploadState().equals("OPEN") || !current.assetState().equals("UPLOADING")) {
            throw new UploadFailure("UPLOAD_STATE_CONFLICT");
        }
        // Sample after acquiring the row lock, including any time spent waiting.
        Instant now = clock.instant();
        if (!current.expiresAt().isAfter(now)) { throw new UploadFailure("UPLOAD_EXPIRED"); }
        int changed = jdbc.update("UPDATE media_uploads SET state = 'SUBMITTED', updated_at = ? WHERE id = ? AND state = 'OPEN'",
                Timestamp.from(now), uploadId);
        changed += jdbc.update("""
                UPDATE media_assets SET state = 'QUEUED', updated_at = ?
                WHERE asset_id = ? AND asset_version = ? AND state = 'UPLOADING'
                """, Timestamp.from(now), assetId, assetVersion);
        if (changed != 2) { throw new UploadFailure("UPLOAD_STATE_CONFLICT"); }
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
                rs.getString("asset_state"), rs.getObject("job_id", UUID.class), rs.getInt("attempt_count"),
                rs.getTimestamp("expires_at").toInstant());
    }

    private static final String SELECT = """
            SELECT u.*, u.state AS upload_state, a.state AS asset_state, j.id AS job_id,
                coalesce(j.attempt_count, 0) AS attempt_count
            FROM media_uploads u
            JOIN media_assets a ON a.asset_id = u.asset_id AND a.asset_version = u.asset_version
            LEFT JOIN media_jobs j ON j.upload_id = u.id
            """;

}
