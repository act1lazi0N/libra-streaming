package com.libra.streaming.media.processing.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Appends the {@code MediaAssetStateChanged} record for an asset transition the caller has just written, inside the
 * caller's transaction: the state change, the next aggregate version and the PENDING outbox row commit or roll back
 * together. Only real transitions call it, so an internal retry or a source/probe step never becomes an event.
 *
 * <p>The row holds the contract payload; the envelope (event ID, type, schema version, aggregate ID and version,
 * occurrence time and correlation ID) is in its columns. The correlation ID is the upload's immutable request ID.
 */
final class MediaAssetEvents {
    enum State { PROCESSING, READY, FAILED }

    private MediaAssetEvents() {}

    /** Returns the new aggregate version. {@code durationSeconds} is required for READY and absent otherwise. */
    static long append(JdbcTemplate jdbc, UUID assetId, long assetVersion, State state, Integer durationSeconds,
            Instant now) {
        if ((state == State.READY) != (durationSeconds != null)) {
            throw new IllegalArgumentException("READY and only READY carries a duration");
        }
        var bumped = jdbc.query("""
                UPDATE media_assets a SET aggregate_version = a.aggregate_version + 1
                FROM media_uploads u
                WHERE a.asset_id = ? AND a.asset_version = ?
                    AND u.asset_id = a.asset_id AND u.asset_version = a.asset_version
                RETURNING a.aggregate_version, a.content_id, a.binding_id, u.request_id
                """, (rs, row) -> new Bumped(rs.getLong(1), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class),
                rs.getObject(4, UUID.class)), assetId, assetVersion);
        if (bumped.size() != 1) { throw new IllegalStateException("Invalid event asset"); }
        var asset = bumped.getFirst();
        // Every value is a UUID, a number or an enum constant, so no escaping is needed.
        var payload = String.format(Locale.ROOT,
                "{\"contentId\":\"%s\",\"bindingId\":\"%s\",\"assetId\":\"%s\",\"assetVersion\":%d,\"state\":\"%s\"%s}",
                asset.contentId(), asset.bindingId(), assetId, assetVersion, state.name(),
                durationSeconds == null ? "" : ",\"durationSeconds\":" + durationSeconds);
        jdbc.update("""
                INSERT INTO media_outbox_events(event_id, asset_id, asset_version, aggregate_version,
                    correlation_id, payload, next_attempt_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?)
                """, UUID.randomUUID(), assetId, assetVersion, asset.aggregateVersion(), asset.requestId(), payload,
                Timestamp.from(now), Timestamp.from(now));
        return asset.aggregateVersion();
    }

    private record Bumped(long aggregateVersion, UUID contentId, UUID bindingId, UUID requestId) {}
}
