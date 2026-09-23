package com.libra.streaming.core.catalog;

import com.libra.streaming.core.api.DomainException;
import com.libra.streaming.core.identity.IdentityAccess;
import com.libra.streaming.core.identity.IdentityPrincipal;
import com.libra.streaming.core.integration.outbox.CoreEventTopic;
import com.libra.streaming.core.integration.outbox.DomainEvents;
import jakarta.validation.Validator;
import java.time.Clock;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.libra.streaming.core.catalog.CatalogModels.*;

@Service
public class CatalogService {
    private final JdbcTemplate jdbc;
    private final CatalogStore store;
    private final IdentityAccess access;
    private final DomainEvents events;
    private final Validator validator;
    private final Clock clock;

    public CatalogService(JdbcTemplate jdbc, CatalogStore store, IdentityAccess access,
            DomainEvents events, Validator validator, Clock clock) {
        this.jdbc = jdbc; this.store = store; this.access = access;
        this.events = events; this.validator = validator; this.clock = clock;
    }

    @Transactional
    public AdminView create(IdentityPrincipal actor, Create request) {
        access.lockCurrent(actor, true);
        validate(request);
        boolean child = request.kind() == Kind.SEASON || request.kind() == Kind.EPISODE;
        if (child != (request.parentId() != null) || child != (request.ordinal() != null)) {
            throw DomainException.invalid();
        }
        if (child) {
            var parent = store.lock(request.parentId());
            Kind required = request.kind() == Kind.SEASON ? Kind.SERIES : Kind.SEASON;
            if (parent.kind() != required) { throw DomainException.conflict("INVALID_HIERARCHY"); }
        }
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("INSERT INTO catalog_contents(id, kind, parent_id, ordinal) VALUES (?, ?, ?, ?)",
                    id, request.kind().name(), request.parentId(), request.ordinal());
        } catch (DuplicateKeyException exception) { throw DomainException.conflict("ORDINAL_CONFLICT"); }
        store.insertRevision(id, 1, request.metadata());
        return store.view(store.lock(id));
    }

    @Transactional
    public AdminView get(IdentityPrincipal actor, UUID id) {
        access.lockCurrent(actor, true);
        return store.view(store.lock(id));
    }

    @Transactional
    public List<AdminView> list(IdentityPrincipal actor, int limit, int offset) {
        access.lockCurrent(actor, true);
        page(limit, offset);
        return jdbc.query("SELECT id FROM catalog_contents ORDER BY created_at DESC, id LIMIT ? OFFSET ?",
                (rs, row) -> rs.getObject(1, UUID.class), limit, offset).stream().map(id -> store.view(store.lock(id))).toList();
    }

    @Transactional
    public List<Revision> revisions(IdentityPrincipal actor, UUID id, int limit, int offset) {
        access.lockCurrent(actor, true);
        page(limit, offset);
        store.lock(id);
        return store.revisions(id, limit, offset);
    }

    @Transactional
    public AdminView edit(IdentityPrincipal actor, UUID id, Edit request) {
        access.lockCurrent(actor, true);
        validate(request);
        var content = checked(id, request.expectedVersion());
        long revision = content.draftRevision() + 1;
        store.insertRevision(id, revision, request.metadata());
        jdbc.update("UPDATE catalog_contents SET draft_revision = ?, version = version + 1 WHERE id = ?", revision, id);
        return store.view(store.lock(id));
    }

    @Transactional
    public AdminView bind(IdentityPrincipal actor, UUID id, Bind request) {
        access.lockCurrent(actor, true);
        validate(request);
        var content = checked(id, request.expectedVersion());
        if (!content.kind().playable()) { throw DomainException.conflict("NOT_PLAYABLE_CONTENT"); }
        UUID bindingId = UUID.randomUUID();
        try {
            jdbc.update("INSERT INTO catalog_media_bindings(id, content_id, asset_id, asset_version) VALUES (?, ?, ?, ?)",
                    bindingId, id, request.assetId(), request.assetVersion());
        } catch (DuplicateKeyException exception) { throw DomainException.conflict("ASSET_ALREADY_BOUND"); }
        jdbc.update("UPDATE catalog_contents SET candidate_binding = ?, version = version + 1 WHERE id = ?", bindingId, id);
        return store.view(store.lock(id));
    }

    @Transactional
    public AdminView publish(IdentityPrincipal actor, UUID id, Version request, UUID correlationId) {
        access.lockCurrent(actor, true);
        validate(request);
        var content = checked(id, request.expectedVersion());
        if (content.kind().playable()) {
            Binding binding = store.binding(content.candidateBinding());
            if (binding == null || !binding.state().equals("READY")) { throw DomainException.conflict("MEDIA_NOT_READY"); }
        }
        jdbc.update("""
                UPDATE catalog_contents SET published_revision = draft_revision, published_at = ?,
                    active_binding = candidate_binding, version = version + 1 WHERE id = ?
                """, Timestamp.from(clock.instant()), id);
        publicationEvent(id, "CatalogPublished", content.version() + 1, correlationId);
        return store.view(store.lock(id));
    }

    @Transactional
    public AdminView unpublish(IdentityPrincipal actor, UUID id, Version request, UUID correlationId) {
        access.lockCurrent(actor, true);
        validate(request);
        var content = checked(id, request.expectedVersion());
        jdbc.update("UPDATE catalog_contents SET published_revision = NULL, published_at = NULL, version = version + 1 WHERE id = ?", id);
        publicationEvent(id, "CatalogUnpublished", content.version() + 1, correlationId);
        return store.view(store.lock(id));
    }

    void publicationEvent(UUID id, String eventType, long version, UUID correlationId) {
        var content = store.lock(id);
        // Parent identity lets consumers apply ancestor visibility without rewriting descendants.
        var payload = new Publication(id, content.kind(), content.parentId(), content.ordinal(),
                content.publishedRevision() == null ? null : store.revision(id, content.publishedRevision()),
                content.publishedRevision() == null ? null : store.binding(content.activeBinding()));
        events.append(CoreEventTopic.CATALOG, eventType, id, version, correlationId, payload);
    }

    private CatalogStore.Content checked(UUID id, long expectedVersion) {
        var content = store.lock(id);
        if (content.version() != expectedVersion) { throw DomainException.conflict("VERSION_CONFLICT"); }
        return content;
    }

    private void validate(Object request) {
        if (request == null || !validator.validate(request).isEmpty()) { throw DomainException.invalid(); }
    }

    static void page(int limit, int offset) {
        if (limit < 1 || limit > 100 || offset < 0 || offset > 10000) { throw DomainException.invalid(); }
    }

    public record Publication(UUID contentId, Kind kind, UUID parentId, Integer ordinal, Revision published, Binding active) {}
}
