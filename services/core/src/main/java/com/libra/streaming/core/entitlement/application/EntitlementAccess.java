package com.libra.streaming.core.entitlement.application;

import com.libra.streaming.core.identity.IdentityPrincipal;
import java.time.Instant;
import java.util.UUID;

/** Reads one committed Core snapshot for an admission decision. */
public interface EntitlementAccess {
    Snapshot current(IdentityPrincipal actor, UUID profileId, UUID contentId);

    record Snapshot(boolean accountActive, boolean emailVerified, boolean sessionNotRevoked, Instant sessionExpiry,
            boolean profileOwned, boolean visible, String kind, Long publishedRevision, String accessTier,
            UUID bindingId, UUID assetId, Long assetVersion, String mediaState, Integer durationSeconds,
            Instant premiumExpiry) {}
}
