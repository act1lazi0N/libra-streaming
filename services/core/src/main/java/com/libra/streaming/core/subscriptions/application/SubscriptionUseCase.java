package com.libra.streaming.core.subscriptions.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/** Framework-free rules; the adapter owns the account lock and database transaction. */
public final class SubscriptionUseCase {
    private static final String PLAN = "PREMIUM_30_DAYS";
    private static final Duration TERM = Duration.ofDays(30);
    private final SubscriptionStore store;
    private final Clock clock;

    public SubscriptionUseCase(SubscriptionStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    public Status status(UUID accountId) {
        Instant expiry = store.expiry(accountId);
        Instant now = clock.instant();
        return new Status(expiry == null ? "NONE" : expiry.isAfter(now) ? "ACTIVE" : "EXPIRED", expiry, true);
    }

    public SubscriptionStore.Purchase activate(UUID accountId, boolean emailVerified, UUID key, String plan) {
        if (!emailVerified) { throw new Failure("EMAIL_VERIFICATION_REQUIRED"); }
        if (key == null || plan == null || plan.isBlank() || plan.length() > 32) {
            throw new Failure("INVALID_REQUEST");
        }
        var previous = store.byIdempotencyKey(accountId, key);
        if (previous.isPresent()) {
            if (!previous.get().plan().equals(plan)) { throw new Failure("IDEMPOTENCY_CONFLICT"); }
            return previous.get();
        }
        if (!PLAN.equals(plan)) { throw new Failure("INVALID_REQUEST"); }
        // PostgreSQL stores microseconds; return the same timestamps that an idempotent replay reads.
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Instant existing = store.expiry(accountId);
        Instant start = existing != null && existing.isAfter(now) ? existing : now;
        Instant expires = start.plus(TERM);
        var purchase = new SubscriptionStore.Purchase(UUID.randomUUID(), PLAN, true, now, start, expires);
        store.save(accountId, key, purchase);
        return purchase;
    }

    public List<SubscriptionStore.Purchase> purchases(UUID accountId, int limit, int offset) {
        if (limit < 1 || limit > 100 || offset < 0 || offset > 10000) {
            throw new Failure("INVALID_REQUEST");
        }
        return store.purchases(accountId, limit, offset);
    }

    public record Status(String status, Instant expiresAt, boolean simulated) {}

    public static final class Failure extends RuntimeException {
        private final String code;

        public Failure(String code) { super(code); this.code = code; }

        public String code() { return code; }
    }
}
