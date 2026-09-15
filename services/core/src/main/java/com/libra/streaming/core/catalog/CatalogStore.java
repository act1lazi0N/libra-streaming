package com.libra.streaming.core.catalog;

import com.libra.streaming.core.api.DomainException;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;
import static com.libra.streaming.core.catalog.CatalogModels.*;

@Repository
public class CatalogStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public CatalogStore(JdbcTemplate jdbc, ObjectMapper mapper) { this.jdbc = jdbc; this.mapper = mapper; }

    Content lock(UUID id) {
        return jdbc.query("SELECT * FROM catalog_contents WHERE id = ? FOR UPDATE", (rs, row) -> new Content(
                id, Kind.valueOf(rs.getString("kind")), rs.getObject("parent_id", UUID.class),
                rs.getObject("ordinal", Integer.class), rs.getLong("version"), rs.getLong("draft_revision"),
                rs.getObject("published_revision", Long.class), rs.getObject("candidate_binding", UUID.class),
                rs.getObject("active_binding", UUID.class)), id).stream().findFirst().orElseThrow(DomainException::missing);
    }

    Revision revision(UUID id, long revision) {
        return jdbc.queryForObject("SELECT * FROM catalog_revisions WHERE content_id = ? AND revision = ?",
                (rs, row) -> new Revision(revision, mapper.readValue(rs.getString("metadata"), Metadata.class),
                        rs.getTimestamp("created_at").toInstant()), id, revision);
    }

    List<Revision> revisions(UUID id, int limit, int offset) {
        return jdbc.query("SELECT * FROM catalog_revisions WHERE content_id = ? ORDER BY revision DESC LIMIT ? OFFSET ?",
                (rs, row) -> new Revision(rs.getLong("revision"), mapper.readValue(rs.getString("metadata"), Metadata.class),
                        rs.getTimestamp("created_at").toInstant()), id, limit, offset);
    }

    void insertRevision(UUID id, long revision, Metadata metadata) {
        jdbc.update("INSERT INTO catalog_revisions(content_id, revision, metadata) VALUES (?, ?, CAST(? AS jsonb))",
                id, revision, mapper.writeValueAsString(metadata));
    }

    Binding binding(UUID id) {
        if (id == null) { return null; }
        return jdbc.query("SELECT * FROM catalog_media_bindings WHERE id = ?", (rs, row) -> new Binding(id,
                rs.getObject("content_id", UUID.class), rs.getObject("asset_id", UUID.class), rs.getLong("asset_version"),
                rs.getLong("projection_version"), rs.getString("state"), rs.getObject("duration_seconds", Integer.class)), id)
                .stream().findFirst().orElseThrow(DomainException::missing);
    }

    AdminView view(Content content) {
        return new AdminView(content.id(), content.kind(), content.parentId(), content.ordinal(), content.version(),
                revision(content.id(), content.draftRevision()), content.publishedRevision() == null ? null
                        : revision(content.id(), content.publishedRevision()), binding(content.candidateBinding()), binding(content.activeBinding()));
    }

    record Content(UUID id, Kind kind, UUID parentId, Integer ordinal, long version, long draftRevision,
            Long publishedRevision, UUID candidateBinding, UUID activeBinding) {}
}
