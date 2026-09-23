package com.libra.streaming.core.entitlement;

import com.libra.streaming.core.catalog.CatalogModels.Tier;
import com.libra.streaming.core.identity.IdentityPrincipal;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class EntitlementController {
    private final EntitlementService entitlements;

    public EntitlementController(EntitlementService entitlements) { this.entitlements = entitlements; }

    @GetMapping("/v1/profiles/{profileId}/entitlements/{contentId}")
    EligibilityView check(@AuthenticationPrincipal IdentityPrincipal actor,
            @PathVariable UUID profileId, @PathVariable UUID contentId) {
        var result = entitlements.requireEligible(actor, profileId, contentId);
        return new EligibilityView(true, result.accessTier(), result.checkedAt());
    }

    // Keep internal asset bindings, account/session identifiers and expiry details out of the public result.
    public record EligibilityView(boolean eligible, Tier accessTier, Instant checkedAt) {}
}
