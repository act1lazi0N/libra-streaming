package com.libra.streaming.core.entitlement.application;

import com.libra.streaming.core.catalog.CatalogModels.Tier;
import com.libra.streaming.core.identity.IdentityPrincipal;
import java.time.Instant;
import java.util.UUID;

public interface EntitlementOperations {
    EligibleContent requireEligible(IdentityPrincipal actor, UUID profileId, UUID contentId);

    /** This decision is not a playback session or reusable media credential. */
    record EligibleContent(UUID profileId, UUID contentId, Tier accessTier, long publishedRevision,
            UUID bindingId, UUID assetId, long assetVersion, int durationSeconds, Instant checkedAt,
            Instant sessionExpiresAt, Instant premiumExpiresAt) {}
}
