package com.libra.streaming.core.integration.operations;

import com.libra.streaming.core.api.DomainException;
import com.libra.streaming.core.identity.IdentityAccess;
import com.libra.streaming.core.identity.IdentityPrincipal;
import com.libra.streaming.core.integration.media.MediaEventDecoder;
import jakarta.validation.Validator;
import jakarta.validation.constraints.*;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OperationsService {
    public enum Action { OUTBOX_RETRY, MEDIA_DLT_REDRIVE }
    public record Command(@NotNull UUID requestId, @NotNull @Min(1) Long expectedVersion,
            @NotBlank @Size(max = 500) String reason) {}
    public record Audit(UUID id, UUID administratorId, UUID requestId, Action action, UUID targetId,
            long expectedVersion, long resultingVersion, String reason, UUID correlationId, Instant createdAt) {}
    public record Work(UUID id, String state, int attempts, long version, String errorCode, Instant createdAt,
            Instant nextAttemptAt, Instant leaseUntil, UUID eventId, boolean payloadAvailable) {}
    public record QueueCount(String queue, String state, long count, Double oldestAgeSeconds) {}
    private final JdbcTemplate jdbc;
    private final IdentityAccess access;
    private final MediaEventDecoder decoder;
    private final Validator validator;
    private final Clock clock;
    private static final org.springframework.jdbc.core.RowMapper<Audit> AUDIT = (rs, row) -> new Audit(
            rs.getObject("id", UUID.class), rs.getObject("administrator_id", UUID.class), rs.getObject("request_id", UUID.class),
            Action.valueOf(rs.getString("action")), rs.getObject("target_id", UUID.class), rs.getLong("expected_version"),
            rs.getLong("resulting_version"), rs.getString("reason"), rs.getObject("correlation_id", UUID.class),
            rs.getTimestamp("created_at").toInstant());
    public OperationsService(JdbcTemplate jdbc, IdentityAccess access, MediaEventDecoder decoder, Validator validator, Clock clock) {
        this.jdbc = jdbc; this.access = access; this.decoder = decoder; this.validator = validator; this.clock = clock;
    }

    @Transactional
    public Audit command(IdentityPrincipal actor, Action action, UUID targetId, Command request, UUID correlationId) {
        access.lockCurrent(actor, true);
        if (request == null || !validator.validate(request).isEmpty() || correlationId == null) { throw DomainException.invalid(); }
        var previous = jdbc.query("SELECT * FROM integration_operation_audit WHERE administrator_id = ? AND request_id = ?",
                AUDIT, actor.accountId(), request.requestId());
        if (!previous.isEmpty()) {
            var audit = previous.getFirst();
            if (audit.action() != action || !audit.targetId().equals(targetId) || audit.expectedVersion() != request.expectedVersion()
                    || !audit.reason().equals(request.reason())) { throw DomainException.conflict("IDEMPOTENCY_CONFLICT"); }
            return audit;
        }
        String table = action == Action.OUTBOX_RETRY ? "outbox_events" : "media_dead_letters";
        String idColumn = action == Action.OUTBOX_RETRY ? "event_id" : "id";
        var rows = jdbc.queryForList("SELECT * FROM " + table + " WHERE " + idColumn + " = ? FOR UPDATE", targetId);
        if (rows.isEmpty()) { throw DomainException.missing(); }
        var row = rows.getFirst(); long version = ((Number) row.get("delivery_version")).longValue();
        if (version != request.expectedVersion()) { throw DomainException.conflict("VERSION_CONFLICT"); }
        String state = (String) row.get("delivery_state");
        if (action == Action.OUTBOX_RETRY && !state.equals("PARKED")) { throw DomainException.conflict("WORK_NOT_PARKED"); }
        if (action == Action.MEDIA_DLT_REDRIVE) {
            if (state.equals("PENDING")) { throw DomainException.conflict("REDRIVE_PENDING"); }
            if (!Boolean.TRUE.equals(row.get("payload_available")) || row.get("source_partition") == null) {
                throw DomainException.conflict("EVENT_NOT_REDRIVABLE");
            }
            com.libra.streaming.core.catalog.MediaProjectionService.Change change;
            try {
                var event = decoder.decode((String) row.get("event_key"), (String) row.get("event_body"));
                change = decoder.validate((String) row.get("event_key"), event);
            } catch (RuntimeException exception) { throw DomainException.conflict("EVENT_NOT_REDRIVABLE"); }
            if (!Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS(SELECT 1 FROM catalog_media_bindings WHERE id = ? AND content_id = ? AND asset_id = ? AND asset_version = ?)
                    """, Boolean.class, change.bindingId(), change.contentId(), change.assetId(), change.assetVersion()))) {
                throw DomainException.conflict("MEDIA_BINDING_UNAVAILABLE");
            }
        }
        jdbc.update("UPDATE " + table + " SET delivery_state = 'PENDING', attempts = 0, next_attempt_at = ?, "
                + "lease_id = NULL, lease_until = NULL, last_error_code = NULL, delivery_version = delivery_version + 1 WHERE "
                + idColumn + " = ?", Timestamp.from(clock.instant()), targetId);
        UUID auditId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO integration_operation_audit(id, administrator_id, request_id, action, target_id,
                    expected_version, resulting_version, reason, correlation_id, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, auditId, actor.accountId(), request.requestId(), action.name(), targetId, version, version + 1,
                request.reason(), correlationId, Timestamp.from(clock.instant()));
        return jdbc.queryForObject("SELECT * FROM integration_operation_audit WHERE id = ?", AUDIT, auditId);
    }

    @Transactional
    public List<Work> work(IdentityPrincipal actor, boolean deadLetters, int limit, int offset) {
        access.lockCurrent(actor, true); page(limit, offset);
        String columns = "delivery_state, attempts, delivery_version, last_error_code, created_at, next_attempt_at, lease_until";
        String sql = deadLetters ? "SELECT id, event_id, payload_available, " + columns + " FROM media_dead_letters ORDER BY received_at DESC, id LIMIT ? OFFSET ?"
                : "SELECT event_id AS id, event_id, TRUE AS payload_available, " + columns + " FROM outbox_events WHERE delivery_state <> 'SENT' ORDER BY created_at, event_id LIMIT ? OFFSET ?";
        return jdbc.query(sql, (rs, row) -> new Work(rs.getObject("id", UUID.class), rs.getString("delivery_state"), rs.getInt("attempts"),
                rs.getLong("delivery_version"), rs.getString("last_error_code"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("next_attempt_at").toInstant(), rs.getTimestamp("lease_until") == null ? null : rs.getTimestamp("lease_until").toInstant(),
                rs.getObject("event_id", UUID.class), rs.getBoolean("payload_available")), limit, offset);
    }
    @Transactional
    public List<Audit> audit(IdentityPrincipal actor, int limit, int offset) {
        access.lockCurrent(actor, true); page(limit, offset);
        return jdbc.query("SELECT * FROM integration_operation_audit ORDER BY created_at DESC, id LIMIT ? OFFSET ?", AUDIT, limit, offset);
    }
    @Transactional
    public List<QueueCount> summary(IdentityPrincipal actor) {
        access.lockCurrent(actor, true);
        return jdbc.query("""
                SELECT queue, delivery_state, count(*) AS count,
                    greatest(0, extract(epoch FROM (? - min(created_at))))::double precision AS age
                FROM (SELECT 'OUTBOX' AS queue, delivery_state, created_at FROM outbox_events WHERE delivery_state <> 'SENT'
                    UNION ALL SELECT 'MEDIA_DLT', delivery_state, created_at FROM media_dead_letters WHERE delivery_state <> 'SENT') work
                GROUP BY queue, delivery_state ORDER BY queue, delivery_state
                """, (rs, row) -> new QueueCount(rs.getString("queue"), rs.getString("delivery_state"), rs.getLong("count"),
                        rs.getObject("age", Double.class)), Timestamp.from(clock.instant()));
    }
    private static void page(int limit, int offset) {
        if (limit < 1 || limit > 100 || offset < 0 || offset > 10000) { throw DomainException.invalid(); }
    }
}
