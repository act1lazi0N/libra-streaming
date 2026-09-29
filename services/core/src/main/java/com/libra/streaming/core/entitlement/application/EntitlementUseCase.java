package com.libra.streaming.core.entitlement.application;

import com.libra.streaming.core.catalog.CatalogModels.Kind;
import com.libra.streaming.core.catalog.CatalogModels.Tier;
import com.libra.streaming.core.identity.IdentityPrincipal;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/** Re-evaluates current admission from one Core-owned snapshot. */
public final class EntitlementUseCase {
    private final EntitlementAccess access;
    private final Clock clock;

    public EntitlementUseCase(EntitlementAccess access, Clock clock) {
        this.access = access;
        this.clock = clock;
    }

    public EntitlementOperations.EligibleContent requireEligible(IdentityPrincipal actor, UUID profileId, UUID contentId) {
        if (actor == null) { throw new Failure(KindOfFailure.UNAUTHENTICATED, "INVALID_CREDENTIALS"); }
        if (profileId == null || contentId == null) { throw new Failure(KindOfFailure.INVALID, "INVALID_REQUEST"); }
        var state = access.current(actor, profileId, contentId);
        Instant now = clock.instant();
        if (state == null || !state.accountActive() || !state.sessionNotRevoked()
                || state.sessionExpiry() == null || !state.sessionExpiry().isAfter(now)) {
            throw new Failure(KindOfFailure.UNAUTHENTICATED, "INVALID_CREDENTIALS");
        }
        if (!state.emailVerified()) { throw new Failure(KindOfFailure.FORBIDDEN, "EMAIL_VERIFICATION_REQUIRED"); }
        // Missing and foreign profiles, drafts and hidden descendants share the same external result.
        if (!state.profileOwned() || !state.visible()) { throw new Failure(KindOfFailure.MISSING, "NOT_FOUND"); }
        if (!Kind.valueOf(state.kind()).playable()) {
            throw new Failure(KindOfFailure.CONFLICT, "NOT_PLAYABLE_CONTENT");
        }
        if (state.bindingId() == null || state.assetId() == null || state.assetVersion() == null
                || !"READY".equals(state.mediaState()) || state.durationSeconds() == null) {
            throw new Failure(KindOfFailure.CONFLICT, "MEDIA_NOT_READY");
        }
        Tier tier = Tier.valueOf(state.accessTier());
        if (tier == Tier.PREMIUM && (state.premiumExpiry() == null || !state.premiumExpiry().isAfter(now))) {
            throw new Failure(KindOfFailure.FORBIDDEN, "PREMIUM_REQUIRED");
        }
        return new EntitlementOperations.EligibleContent(profileId, contentId, tier, state.publishedRevision(),
                state.bindingId(), state.assetId(), state.assetVersion(), state.durationSeconds(), now,
                state.sessionExpiry(), tier == Tier.PREMIUM ? state.premiumExpiry() : null);
    }

    public enum KindOfFailure { UNAUTHENTICATED, INVALID, FORBIDDEN, MISSING, CONFLICT }

    public static final class Failure extends RuntimeException {
        private final KindOfFailure kind;

        public Failure(KindOfFailure kind, String code) { super(code); this.kind = kind; }

        public KindOfFailure kind() { return kind; }
    }
}
