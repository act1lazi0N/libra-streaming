package com.libra.streaming.core.subscriptions.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SubscriptionStore {
    Instant expiry(UUID accountId);

    Optional<Purchase> byIdempotencyKey(UUID accountId, UUID key);

    void save(UUID accountId, UUID key, Purchase purchase);

    List<Purchase> purchases(UUID accountId, int limit, int offset);

    record Purchase(UUID id, String plan, boolean simulated, Instant purchasedAt,
            Instant termStart, Instant expiresAt) {}
}
