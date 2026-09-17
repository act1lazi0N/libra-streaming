package com.libra.streaming.core.entitlement;

import com.libra.streaming.core.api.DomainException;
import com.libra.streaming.core.catalog.CatalogModels.Kind;
import com.libra.streaming.core.catalog.CatalogModels.Tier;
import com.libra.streaming.core.identity.IdentityException;
import com.libra.streaming.core.identity.IdentityPrincipal;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Current eligibility only. A result is neither a playback session nor a reusable media credential. */
@Service
public class EntitlementService {
    private final EntitlementStore store;
    private final Clock clock;

    EntitlementService(EntitlementStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public EligibleContent requireEligible(IdentityPrincipal actor, UUID profileId, UUID contentId) {
        if (actor == null) { throw IdentityException.unauthenticated(); }
        if (profileId == null || contentId == null) { throw DomainException.invalid(); }
        var state = store.current(actor, profileId, contentId);
        Instant now = clock.instant();
        if (!state.accountActive() || !state.sessionNotRevoked() || state.sessionExpiry() == null
                || !state.sessionExpiry().isAfter(now)) {
            throw IdentityException.unauthenticated();
        }
        if (!state.emailVerified()) { throw forbidden("EMAIL_VERIFICATION_REQUIRED"); }
        // Missing and foreign profiles, drafts and hidden descendants have the same external result.
        if (!state.profileOwned() || !state.visible()) { throw DomainException.missing(); }
        if (!Kind.valueOf(state.kind()).playable()) { throw DomainException.conflict("NOT_PLAYABLE_CONTENT"); }
        if (state.bindingId() == null || state.assetId() == null || state.assetVersion() == null
                || !"READY".equals(state.mediaState()) || state.durationSeconds() == null) {
            throw DomainException.conflict("MEDIA_NOT_READY");
        }
        Tier tier = Tier.valueOf(state.accessTier());
        if (tier == Tier.PREMIUM && (state.premiumExpiry() == null || !state.premiumExpiry().isAfter(now))) {
            throw forbidden("PREMIUM_REQUIRED");
        }
        return new EligibleContent(profileId, contentId, tier, state.publishedRevision(), state.bindingId(),
                state.assetId(), state.assetVersion(), state.durationSeconds(), now, state.sessionExpiry(),
                tier == Tier.PREMIUM ? state.premiumExpiry() : null);
    }

    private static DomainException forbidden(String code) { return new DomainException(HttpStatus.FORBIDDEN, code); }

    /** Internal selection for future admission; consumers must reevaluate on each admission/renewal. */
    public record EligibleContent(UUID profileId, UUID contentId, Tier accessTier, long publishedRevision,
            UUID bindingId, UUID assetId, long assetVersion, int durationSeconds, Instant checkedAt,
            Instant sessionExpiresAt, Instant premiumExpiresAt) {}
}
