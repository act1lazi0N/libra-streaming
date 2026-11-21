package com.libra.streaming.recommendation.catalog.application;

import com.libra.streaming.recommendation.catalog.domain.CatalogNode;
import com.libra.streaming.recommendation.ingestion.domain.CatalogChange;

/** Latest version wins per content node; an event at or below the stored version changes nothing (RA01.1). */
public class CatalogProjection {
    private final CatalogStore store;

    public CatalogProjection(CatalogStore store) { this.store = store; }

    public CatalogStore.Write apply(CatalogChange change) { return store.upsert(CatalogNode.of(change)); }

    /** A bootstrap snapshot item goes through the same version rule as a live event (RA02.4). */
    public CatalogStore.Write apply(CatalogNode snapshot) { return store.upsert(snapshot); }
}
