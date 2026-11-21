package com.libra.streaming.recommendation.playback.application;

import com.libra.streaming.recommendation.ingestion.domain.PlaybackProgress;
import com.libra.streaming.recommendation.playback.domain.Qualification;
import com.libra.streaming.recommendation.playback.domain.StoredFact;
import com.libra.streaming.recommendation.preferences.application.ProfilePreferences;
import com.libra.streaming.recommendation.statistics.application.PlatformStatistics;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Playback is facts, not state: each accepted event adds its own increment, so a late or reordered event still
 * counts once (RA01.1). Duplicates never reach this class; the intake's receipt stops them in the same transaction.
 * The daily statistics (RA04.1) and the profile's watched time for genre interests (RA05.1) are updated in that same
 * transaction; the latter only while the fact keeps its profile link.
 */
public class PlaybackProjection {
    private final PlaybackStore store;
    private final PlatformStatistics statistics;
    private final ProfilePreferences preferences;

    public PlaybackProjection(PlaybackStore store, PlatformStatistics statistics, ProfilePreferences preferences) {
        this.store = store;
        this.statistics = statistics;
        this.preferences = preferences;
    }

    public void apply(PlaybackProgress progress) {
        StoredFact stored = store.append(progress);
        Instant occurredAt = storedInstant(progress.envelope().occurredAt());
        Qualification qualification = stored.qualification();
        statistics.record(progress.accountId(), occurredAt, progress.acceptedWatchedMs(), qualification.before(),
                qualification.after());
        // The store decided the link under the profile's shared lock, which this transaction still holds: a tombstone
        // committed earlier left no link, and one committing later erases what is written here.
        if (stored.profileLink() != null) {
            preferences.record(stored.profileLink(), progress.accountId(), progress.contentId(), occurredAt,
                    progress.acceptedWatchedMs());
        }
    }

    /**
     * The instant as PostgreSQL keeps it (microseconds). The fact, its session's qualifying instant and the day its
     * watched time is bucketed into all use this value, so a fact at 23:59:59.9999995Z cannot put its view and its
     * watched time on different days.
     */
    public static Instant storedInstant(Instant occurredAt) { return occurredAt.truncatedTo(ChronoUnit.MICROS); }
}
