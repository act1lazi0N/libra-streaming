package com.libra.streaming.core.catalog;

import com.libra.streaming.core.integration.outbox.EventEnvelope;
import jakarta.validation.Validator;
import jakarta.validation.constraints.*;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import static com.libra.streaming.core.catalog.CatalogModels.*;

/** Only the broker adapter calls this operation; public/admin HTTP never sets readiness. */
@Service
public class MediaProjectionService {
    private final JdbcTemplate jdbc;
    private final CatalogStore store;
    private final CatalogService catalog;
    private final ObjectMapper mapper;
    private final Validator validator;

    public MediaProjectionService(JdbcTemplate jdbc, CatalogStore store, CatalogService catalog,
            ObjectMapper mapper, Validator validator) {
        this.jdbc = jdbc; this.store = store; this.catalog = catalog; this.mapper = mapper; this.validator = validator;
    }

    @Transactional
    public void accept(String key, EventEnvelope event) {
        if (!event.eventType().equals("MediaAssetStateChanged") || !event.aggregateId().toString().equals(key)) {
            throw new IllegalArgumentException("Invalid media event envelope");
        }
        Change change = mapper.treeToValue(event.payload(), Change.class);
        if (!validator.validate(change).isEmpty() || !event.aggregateId().equals(change.assetId())
                || (change.state() == MediaState.READY && change.durationSeconds() == null)) {
            throw new IllegalArgumentException("Invalid media state payload");
        }
        var content = store.lock(change.contentId());
        Binding binding = store.binding(change.bindingId());
        if (!binding.contentId().equals(content.id()) || !binding.assetId().equals(change.assetId())
                || binding.assetVersion() != change.assetVersion()) {
            throw new IllegalArgumentException("Media binding mismatch");
        }
        int inserted = jdbc.update("INSERT INTO media_projection_receipts(event_id, binding_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
                event.eventId(), binding.id());
        if (inserted == 0 || event.aggregateVersion() <= binding.projectionVersion()) { return; }
        jdbc.update("""
                UPDATE catalog_media_bindings SET projection_version = ?, state = ?, duration_seconds = ? WHERE id = ?
                """, event.aggregateVersion(), change.state().name(),
                change.state() == MediaState.READY ? change.durationSeconds() : null, binding.id());
        // A candidate update does not change editorial publication or the selected active binding.
        if (binding.id().equals(content.activeBinding()) && content.publishedRevision() != null) {
            jdbc.update("UPDATE catalog_contents SET version = version + 1 WHERE id = ?", content.id());
            catalog.publicationEvent(content.id(), "CatalogAvailabilityChanged", content.version() + 1, event.correlationId());
        }
    }

    public record Change(@NotNull UUID contentId, @NotNull UUID bindingId, @NotNull UUID assetId,
            @Min(1) long assetVersion, @NotNull MediaState state, @Min(1) @Max(604800) Integer durationSeconds) {}
}
