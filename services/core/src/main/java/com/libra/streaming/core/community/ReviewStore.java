package com.libra.streaming.core.community;

import com.libra.streaming.core.api.DomainException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import static com.libra.streaming.core.community.CommunityModels.*;

@Repository
public class ReviewStore {
    private static final RowMapper<OwnReview> VIEW = (rs, row) -> new OwnReview(rs.getObject("id", UUID.class),
            rs.getObject("content_id", UUID.class), rs.getObject("stars", Integer.class), rs.getString("text"),
            rs.getBoolean("hidden"), rs.getBoolean("deleted"), rs.getLong("version"), rs.getTimestamp("updated_at").toInstant());
    private final JdbcTemplate jdbc;
    public ReviewStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    Optional<OwnReview> owned(UUID accountId, UUID contentId) {
        return jdbc.query("SELECT * FROM reviews WHERE account_id = ? AND content_id = ? FOR UPDATE", VIEW,
                accountId, contentId).stream().findFirst();
    }
    OwnReview get(UUID id) {
        return jdbc.query("SELECT * FROM reviews WHERE id = ?", VIEW, id)
                .stream().findFirst().orElseThrow(DomainException::missing);
    }
    OwnReview lock(UUID id) {
        return jdbc.query("SELECT * FROM reviews WHERE id = ? FOR UPDATE", VIEW, id)
                .stream().findFirst().orElseThrow(DomainException::missing);
    }
    boolean authoredBy(UUID id, UUID accountId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT account_id = ? FROM reviews WHERE id = ?",
                Boolean.class, accountId, id));
    }
    boolean qualified(UUID accountId, UUID targetId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM playback_sessions s
                JOIN catalog_contents c ON c.id = s.content_id
                LEFT JOIN catalog_contents season ON season.id = c.parent_id
                WHERE s.account_id = ? AND s.watched_ms >= 30000
                    AND (s.content_id = ? OR (c.kind = 'EPISODE' AND season.parent_id = ?)))
                """, Boolean.class, accountId, targetId, targetId));
    }
    OwnReview insert(UUID accountId, UUID contentId, WriteReview request, Instant now) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO reviews(id, account_id, content_id, stars, text, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, id, accountId, contentId, request.stars(), request.text(), Timestamp.from(now), Timestamp.from(now));
        return get(id);
    }
    OwnReview edit(UUID id, WriteReview request, Instant now) {
        jdbc.update("""
                UPDATE reviews SET stars = ?, text = ?, deleted = FALSE, version = version + 1, updated_at = ? WHERE id = ?
                """, request.stars(), request.text(), Timestamp.from(now), id);
        return get(id);
    }
    void delete(UUID id, Instant now) {
        jdbc.update("""
                UPDATE reviews SET stars = NULL, text = NULL, deleted = TRUE, version = version + 1, updated_at = ? WHERE id = ?
                """, Timestamp.from(now), id);
    }
    ReviewPage publicPage(UUID contentId, int limit, int offset) {
        var summary = jdbc.queryForMap("""
                SELECT count(*) AS count, round(avg(stars), 2) AS average FROM reviews
                WHERE content_id = ? AND NOT hidden AND NOT deleted
                """, contentId);
        var items = jdbc.query("""
                SELECT r.id, a.display_name, r.stars, r.text, r.updated_at FROM reviews r
                JOIN identity_accounts a ON a.id = r.account_id
                WHERE r.content_id = ? AND NOT r.hidden AND NOT r.deleted
                ORDER BY r.updated_at DESC, r.id LIMIT ? OFFSET ?
                """, (rs, row) -> new PublicReview(rs.getObject("id", UUID.class), rs.getString("display_name"),
                        rs.getInt("stars"), rs.getString("text"), rs.getTimestamp("updated_at").toInstant()), contentId, limit, offset);
        return new ReviewPage(((Number) summary.get("count")).longValue(), (java.math.BigDecimal) summary.get("average"), items);
    }
    void report(UUID reviewId, UUID reporterId, String reason, long version, Instant now) {
        jdbc.update("""
                INSERT INTO review_reports(id, review_id, reporter_id, reason, review_version, created_at)
                VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (review_id, reporter_id) DO NOTHING
                """, UUID.randomUUID(), reviewId, reporterId, reason, version, Timestamp.from(now));
    }
    OwnReview moderate(OwnReview previous, IdentityAudit actor, Moderate request, Instant now) {
        jdbc.update("UPDATE reviews SET hidden = ?, version = version + 1, updated_at = ? WHERE id = ?",
                request.visibility() == Visibility.HIDDEN, Timestamp.from(now), previous.id());
        jdbc.update("""
                INSERT INTO review_moderation_audit(id, review_id, administrator_id, action, reason,
                    previous_hidden, review_version, correlation_id, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), previous.id(), actor.accountId(), request.visibility().name(), request.reason(),
                previous.hidden(), previous.version() + 1, actor.correlationId(), Timestamp.from(now));
        return get(previous.id());
    }
    List<AdminReview> adminPage(boolean reportedOnly, int limit, int offset) {
        return jdbc.query("""
                SELECT r.*, (SELECT count(*) FROM review_reports p WHERE p.review_id = r.id) AS report_count
                FROM reviews r WHERE (? = FALSE OR EXISTS(SELECT 1 FROM review_reports p WHERE p.review_id = r.id))
                ORDER BY r.updated_at DESC, r.id LIMIT ? OFFSET ?
                """, (rs, row) -> new AdminReview(VIEW.mapRow(rs, row), rs.getLong("report_count")), reportedOnly, limit, offset);
    }
    List<ReportView> reports(UUID id, int limit, int offset) {
        return jdbc.query("""
                SELECT * FROM review_reports WHERE review_id = ? ORDER BY created_at DESC, id LIMIT ? OFFSET ?
                """, (rs, row) -> new ReportView(rs.getObject("id", UUID.class), rs.getString("reason"),
                        rs.getLong("review_version"), rs.getTimestamp("created_at").toInstant()), id, limit, offset);
    }
    List<AuditView> audit(UUID id, int limit, int offset) {
        return jdbc.query("""
                SELECT * FROM review_moderation_audit WHERE review_id = ? ORDER BY review_version DESC LIMIT ? OFFSET ?
                """, (rs, row) -> new AuditView(rs.getObject("id", UUID.class), rs.getObject("administrator_id", UUID.class),
                        Visibility.valueOf(rs.getString("action")), rs.getString("reason"), rs.getBoolean("previous_hidden"),
                        rs.getLong("review_version"), rs.getObject("correlation_id", UUID.class),
                        rs.getTimestamp("created_at").toInstant()), id, limit, offset);
    }
    record IdentityAudit(UUID accountId, UUID correlationId) {}
}
