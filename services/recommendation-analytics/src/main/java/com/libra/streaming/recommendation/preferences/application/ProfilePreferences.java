package com.libra.streaming.recommendation.preferences.application;

import com.libra.streaming.recommendation.preferences.domain.GenreInterests;
import com.libra.streaming.recommendation.preferences.domain.InterestRules;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;

/**
 * Genre interests per profile (RA05.1). Recording happens in the playback intake transaction, only for a fact that
 * keeps its profile link; a profile's data is personal and is erased with its tombstone. Reading never writes.
 */
public class ProfilePreferences {
    private final PreferenceStore store;
    private final InterestRules rules;
    private final Clock clock;

    public ProfilePreferences(PreferenceStore store, InterestRules rules, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.rules = Objects.requireNonNull(rules, "rules");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Adds one accepted increment. Must run in the transaction that stores the fact, so a redelivered fact (stopped by
     * the receipt) never reaches it. Seeking and pauses earn nothing: Core sends {@code acceptedWatchedMs = 0}.
     *
     * @param occurredAt the fact's instant as stored (microseconds), so the day matches the fact and the statistics
     */
    public void record(UUID profileId, UUID accountId, UUID contentId, Instant occurredAt, long acceptedWatchedMs) {
        Objects.requireNonNull(profileId, "profileId");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(contentId, "contentId");
        if (acceptedWatchedMs < 0) { throw new IllegalArgumentException("acceptedWatchedMs"); }
        if (acceptedWatchedMs == 0) { return; }
        store.add(profileId, accountId, contentId, day(occurredAt), acceptedWatchedMs);
    }

    /** Interests from the last {@link InterestRules#windowDays} UTC days, today included, by this host's clock. */
    public GenreInterests interests(UUID accountId, UUID profileId) {
        LocalDate fromDay = day(clock.instant()).minusDays(rules.windowDays() - 1L);
        return GenreInterests.of(store.watched(accountId, profileId, fromDay), rules);
    }

    public InterestRules rules() { return rules; }

    static LocalDate day(Instant instant) { return LocalDate.ofInstant(instant, ZoneOffset.UTC); }
}
