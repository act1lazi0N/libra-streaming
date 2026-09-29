package com.libra.streaming.core.subscriptions.application;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class SubscriptionUseCaseTest {
    @Test
    void retryReturnsSamePurchaseAndNextKeyExtendsTerm() {
        var store = new MemoryStore();
        var clock = Clock.fixed(Instant.parse("2026-09-29T00:00:00.123456789Z"), ZoneOffset.UTC);
        var useCase = new SubscriptionUseCase(store, clock);
        UUID account = UUID.randomUUID();
        UUID firstKey = UUID.randomUUID();
        var first = useCase.activate(account, true, firstKey, "PREMIUM_30_DAYS");
        var retry = useCase.activate(account, true, firstKey, "PREMIUM_30_DAYS");
        var second = useCase.activate(account, true, UUID.randomUUID(), "PREMIUM_30_DAYS");

        assertThat(retry).isEqualTo(first);
        assertThat(store.purchases).hasSize(2);
        assertThat(first.purchasedAt()).isEqualTo(Instant.parse("2026-09-29T00:00:00.123456Z"));
        assertThat(second.termStart()).isEqualTo(first.expiresAt());
        assertThat(second.expiresAt()).isEqualTo(first.expiresAt().plusSeconds(30L * 24 * 60 * 60));
        assertThatThrownBy(() -> useCase.activate(account, true, firstKey, "ANOTHER_PLAN"))
                .isInstanceOf(SubscriptionUseCase.Failure.class).hasMessage("IDEMPOTENCY_CONFLICT");
        assertThat(store.purchases).hasSize(2);
    }

    @Test
    void unverifiedAccountCannotPurchase() {
        var store = new MemoryStore();
        var useCase = new SubscriptionUseCase(store, Clock.systemUTC());
        assertThatThrownBy(() -> useCase.activate(UUID.randomUUID(), false, UUID.randomUUID(), "PREMIUM_30_DAYS"))
                .isInstanceOf(SubscriptionUseCase.Failure.class).hasMessage("EMAIL_VERIFICATION_REQUIRED");
        assertThat(store.purchases).isEmpty();
    }

    private static final class MemoryStore implements SubscriptionStore {
        private final List<Purchase> purchases = new ArrayList<>();
        private final java.util.Map<UUID, Purchase> byKey = new java.util.HashMap<>();
        private Instant expiresAt;

        @Override public Instant expiry(UUID ignored) { return expiresAt; }
        @Override public Optional<Purchase> byIdempotencyKey(UUID ignored, UUID key) {
            return Optional.ofNullable(byKey.get(key));
        }
        @Override public void save(UUID ignored, UUID key, Purchase purchase) {
            purchases.add(purchase);
            byKey.put(key, purchase);
            expiresAt = purchase.expiresAt();
        }
        @Override public List<Purchase> purchases(UUID ignored, int limit, int offset) { return purchases; }
    }
}
