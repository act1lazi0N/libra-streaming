package com.libra.streaming.recommendation.catalog.application;

import com.libra.streaming.recommendation.catalog.domain.CatalogNode;
import com.libra.streaming.recommendation.catalog.domain.Placement;
import com.libra.streaming.recommendation.ingestion.domain.EventRejection;
import java.util.Optional;
import java.util.UUID;

/** Versioned catalog read model. Joins the caller's transaction. */
public interface CatalogStore {
    enum Write { APPLIED, STALE }

    /**
     * Stores the node when its version is newer than the stored one, atomically against concurrent writers.
     *
     * @return STALE when the stored version is the same or newer (nothing changes)
     * @throws EventRejection EVENT_CONFLICT when the node's kind or parent differs from the stored node's
     */
    Write upsert(CatalogNode node);

    Optional<CatalogNode> find(UUID contentId);

    /** Empty when the node itself is unknown. */
    Optional<Placement> placement(UUID contentId);
}
