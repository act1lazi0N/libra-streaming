package com.libra.streaming.core.entitlement.application;

import com.libra.streaming.core.identity.IdentityPrincipal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class EntitlementUseCaseTest {
    private static final Instant NOW = Instant.parse("2026-09-29T00:00:00Z");
    private final IdentityPrincipal actor = new IdentityPrincipal(UUID.randomUUID(), UUID.randomUUID(), "USER", true);
    private final UUID profileId = UUID.randomUUID();
    private final UUID contentId = UUID.randomUUID();

    @Test
    void foreignProfileIsMaskedAndPremiumRequiresCurrentExpiry() {
        var foreign = snapshot(false, "PREMIUM", NOW.plusSeconds(3600));
        assertFailure(foreign, "NOT_FOUND");
        var expired = snapshot(true, "PREMIUM", NOW.minusSeconds(1));
        assertFailure(expired, "PREMIUM_REQUIRED");
        var eligible = new EntitlementUseCase((a, p, c) -> snapshot(true, "PREMIUM", NOW.plusSeconds(3600)),
                Clock.fixed(NOW, ZoneOffset.UTC)).requireEligible(actor, profileId, contentId);
        assertThat(eligible.premiumExpiresAt()).isEqualTo(NOW.plusSeconds(3600));
    }

    @Test
    void missingMediaBindingCannotBeAdmitted() {
        var missing = new EntitlementAccess.Snapshot(true, true, true, NOW.plusSeconds(3600), true, true,
                "MOVIE", 1L, "FREE", null, null, null, null, null, null);
        assertFailure(missing, "MEDIA_NOT_READY");
    }

    private void assertFailure(EntitlementAccess.Snapshot snapshot, String code) {
        var useCase = new EntitlementUseCase((a, p, c) -> snapshot, Clock.fixed(NOW, ZoneOffset.UTC));
        assertThatThrownBy(() -> useCase.requireEligible(actor, profileId, contentId))
                .isInstanceOf(EntitlementUseCase.Failure.class).hasMessage(code);
    }

    private EntitlementAccess.Snapshot snapshot(boolean owned, String tier, Instant premiumExpiry) {
        return new EntitlementAccess.Snapshot(true, true, true, NOW.plusSeconds(3600), owned, true,
                "MOVIE", 1L, tier, UUID.randomUUID(), UUID.randomUUID(), 1L, "READY", 600, premiumExpiry);
    }
}
