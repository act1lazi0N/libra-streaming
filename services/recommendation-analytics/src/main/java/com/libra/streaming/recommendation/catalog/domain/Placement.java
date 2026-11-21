package com.libra.streaming.recommendation.catalog.domain;

import com.libra.streaming.recommendation.ingestion.domain.CatalogChange;
import java.util.UUID;

/**
 * Where a node sits in the movie/series tree, resolved along its parent chain at read time.
 *
 * @param rootId the movie or series the node belongs to; null while the chain is incomplete
 * @param complete every ancestor up to a root is known
 * @param visible the node and every ancestor are published (false when incomplete)
 */
public record Placement(UUID contentId, CatalogChange.Kind kind, UUID rootId, boolean complete, boolean visible) {}
