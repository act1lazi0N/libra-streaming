package com.libra.streaming.core.subscriptions.application;

import com.libra.streaming.core.identity.IdentityPrincipal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Public application boundary used by the HTTP adapter. */
public interface SubscriptionOperations {
    StatusView status(IdentityPrincipal actor);

    PurchaseView activate(IdentityPrincipal actor, UUID key, String plan);

    List<PurchaseView> purchases(IdentityPrincipal actor, int limit, int offset);

    record StatusView(String status, Instant expiresAt, boolean simulated) {}

    record PurchaseView(UUID id, String plan, boolean simulated, Instant purchasedAt,
            Instant termStart, Instant expiresAt) {}
}
