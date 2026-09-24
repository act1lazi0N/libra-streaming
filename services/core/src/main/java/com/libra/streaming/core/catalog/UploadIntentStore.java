package com.libra.streaming.core.catalog;

import com.libra.streaming.core.api.DomainException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
class UploadIntentStore {
    private final JdbcTemplate jdbc;

    UploadIntentStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    UploadIntent findByRequest(UUID creatorId, UUID requestId) {
        return jdbc.query(SELECT + " WHERE i.creator_account_id = ? AND i.request_id = ?",
                (rs, row) -> map(rs), creatorId, requestId).stream().findFirst().orElse(null);
    }

    UploadIntent owned(UUID creatorId, UUID uploadId) {
        return jdbc.query(SELECT + " WHERE i.creator_account_id = ? AND i.id = ?",
                (rs, row) -> map(rs), creatorId, uploadId).stream().findFirst().orElseThrow(DomainException::missing);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    void insert(UploadIntent intent) {
        jdbc.update("""
                INSERT INTO catalog_upload_intents(id, creator_account_id, request_id, content_id, binding_id,
                    expected_version, catalog_version_at_reservation, byte_length, expected_sha256,
                    request_fingerprint, created_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, intent.id(), intent.creatorId(), intent.requestId(), intent.contentId(), intent.bindingId(),
                intent.expectedVersion(), intent.catalogVersionAtReservation(), intent.byteLength(), intent.sha256(),
                intent.fingerprint(), Timestamp.from(intent.createdAt()), Timestamp.from(intent.expiresAt()));
    }

    private static UploadIntent map(ResultSet rs) throws SQLException {
        return new UploadIntent(rs.getObject("id", UUID.class), rs.getObject("creator_account_id", UUID.class),
                rs.getObject("request_id", UUID.class), rs.getObject("content_id", UUID.class),
                rs.getObject("binding_id", UUID.class), rs.getObject("asset_id", UUID.class),
                rs.getLong("asset_version"), rs.getLong("expected_version"),
                rs.getLong("catalog_version_at_reservation"), rs.getLong("byte_length"),
                rs.getString("expected_sha256"), rs.getString("request_fingerprint"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("expires_at").toInstant());
    }

    private static final String SELECT = """
            SELECT i.*, b.asset_id, b.asset_version
            FROM catalog_upload_intents i
            JOIN catalog_media_bindings b ON b.id = i.binding_id AND b.content_id = i.content_id
            """;

    record UploadIntent(UUID id, UUID creatorId, UUID requestId, UUID contentId, UUID bindingId,
            UUID assetId, long assetVersion, long expectedVersion, long catalogVersionAtReservation,
            long byteLength, String sha256, String fingerprint, Instant createdAt, Instant expiresAt) {}
}
