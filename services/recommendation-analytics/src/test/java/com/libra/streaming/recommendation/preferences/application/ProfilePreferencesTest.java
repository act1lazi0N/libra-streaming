package com.libra.streaming.recommendation.preferences.application;

import com.libra.streaming.recommendation.preferences.domain.InterestRules;
import com.libra.streaming.recommendation.preferences.domain.WatchedItem;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ProfilePreferencesTest {
    private final UUID account = new UUID(0, 1);
    private final UUID profile = new UUID(0, 2);
    private final UUID content = new UUID(0, 3);
    private final MemoryStore store = new MemoryStore();

    @Test
    void zeroWritesNothingAndNegativeTimeIsRejected() {
        var preferences = preferences("2026-09-07T12:00:00Z");
        preferences.record(profile, account, content, Instant.EPOCH, 0);
        assertThatIllegalArgumentException().isThrownBy(() ->
                preferences.record(profile, account, content, Instant.EPOCH, -1));
        assertThat(store.writes).isEmpty();
        assertThat(store.reads).isEmpty();
    }

    @Test
    void recordsOnTheUtcDayOfTheFact() {
        var preferences = preferences("2026-09-07T12:00:00Z");
        preferences.record(profile, account, content, Instant.parse("2026-09-07T23:59:59.999999Z"), 12);
        preferences.record(profile, account, content, Instant.parse("2026-09-08T00:00:00Z"), 34);
        assertThat(store.writes).containsExactly(
                new Write(profile, account, content, LocalDate.parse("2026-09-07"), 12),
                new Write(profile, account, content, LocalDate.parse("2026-09-08"), 34));
    }

    @Test
    void readsThirtyDaysIncludingTodayAndMovesAtUtcMidnight() {
        assertThat(preferences("2026-09-07T23:59:59.999999Z").interests(account, profile).isEmpty()).isTrue();
        preferences("2026-09-08T00:00:00Z").interests(account, profile);
        assertThat(store.reads).containsExactly(new Read(account, profile, LocalDate.parse("2026-08-09")),
                new Read(account, profile, LocalDate.parse("2026-08-10")));
        assertThat(store.writes).isEmpty();
    }

    private ProfilePreferences preferences(String now) {
        return new ProfilePreferences(store, InterestRules.DEFAULT,
                Clock.fixed(Instant.parse(now), ZoneOffset.ofHours(7)));
    }

    private record Write(UUID profile, UUID account, UUID content, LocalDate day, long watched) {}
    private record Read(UUID account, UUID profile, LocalDate fromDay) {}

    private static final class MemoryStore implements PreferenceStore {
        final List<Write> writes = new ArrayList<>();
        final List<Read> reads = new ArrayList<>();
        @Override
        public void add(UUID profile, UUID account, UUID content, LocalDate day, long watched) {
            writes.add(new Write(profile, account, content, day, watched));
        }
        @Override
        public List<WatchedItem> watched(UUID account, UUID profile, LocalDate fromDay) {
            reads.add(new Read(account, profile, fromDay));
            return List.of();
        }
    }
}
