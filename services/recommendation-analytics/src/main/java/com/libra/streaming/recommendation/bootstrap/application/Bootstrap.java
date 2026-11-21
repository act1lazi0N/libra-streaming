package com.libra.streaming.recommendation.bootstrap.application;

import com.libra.streaming.recommendation.bootstrap.domain.BootstrapSource;
import com.libra.streaming.recommendation.bootstrap.domain.SnapshotPages;
import com.libra.streaming.recommendation.catalog.application.CatalogProjection;
import com.libra.streaming.recommendation.catalog.domain.CatalogNode;
import com.libra.streaming.recommendation.freshness.domain.InputTopic;
import com.libra.streaming.recommendation.ingestion.application.EventIntake;
import com.libra.streaming.recommendation.ingestion.application.Transactions;
import com.libra.streaming.recommendation.ingestion.domain.EventRejection;
import com.libra.streaming.recommendation.profiles.application.ProfileProjection;
import java.time.Clock;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Builds the read models from Core when Kafka no longer holds the history (RA02.4): profile and catalog snapshots
 * through the same version and tombstone rules as live events, and a replay of every playback event through the
 * same intake (receipt, projection) as a live record. Live consumers may run at the same time; the version rules and
 * the receipts make the result independent of the interleaving.
 *
 * <p>Progress is stored per generation and source after each page. A crash repeats at most one page, which changes
 * nothing the second time. A new generation starts from the beginning and, because every step is idempotent, ends
 * in the same state.
 */
public class Bootstrap {
    public record Report(long generation, Map<BootstrapSource, BootstrapProgress.Position> sources) {
        public Report { sources = Map.copyOf(sources); }

        public boolean completed() { return sources.values().stream().allMatch(BootstrapProgress.Position::completed); }
    }

    private final CoreSnapshots core;
    private final BootstrapProgress progress;
    private final Transactions transactions;
    private final ProfileProjection profiles;
    private final CatalogProjection catalog;
    private final EventIntake intake;
    private final Clock clock;

    public Bootstrap(CoreSnapshots core, BootstrapProgress progress, Transactions transactions,
            ProfileProjection profiles, CatalogProjection catalog, EventIntake intake, Clock clock) {
        this.core = core;
        this.progress = progress;
        this.transactions = transactions;
        this.profiles = profiles;
        this.catalog = catalog;
        this.intake = intake;
        this.clock = clock;
    }

    /**
     * Runs or resumes every source of {@code generation} to completion.
     *
     * @throws CoreSnapshots.SnapshotUnavailableException or a database failure: the stored position is kept and a
     *         later call resumes from it
     */
    public Report run(long generation, int pageSize) {
        if (generation < 1) { throw new IllegalArgumentException("generation"); }
        if (pageSize < 1 || pageSize > 500) { throw new IllegalArgumentException("pageSize"); }
        var sources = new EnumMap<BootstrapSource, BootstrapProgress.Position>(BootstrapSource.class);
        for (BootstrapSource source : BootstrapSource.values()) { sources.put(source, run(generation, source, pageSize)); }
        return new Report(generation, sources);
    }

    public boolean completed(long generation) {
        for (BootstrapSource source : BootstrapSource.values()) {
            if (!progress.find(generation, source).map(BootstrapProgress.Position::completed).orElse(false)) {
                return false;
            }
        }
        return true;
    }

    private BootstrapProgress.Position run(long generation, BootstrapSource source, int pageSize) {
        var position = transactions.inTransaction(() -> progress.start(generation, source, clock.instant()));
        while (!position.completed()) {
            position = switch (source) {
                case PROFILES -> profiles(generation, position, pageSize);
                case CATALOG -> catalog(generation, position, pageSize);
                case PLAYBACK -> playback(generation, position, pageSize);
            };
        }
        return position;
    }

    private BootstrapProgress.Position profiles(long generation, BootstrapProgress.Position at, int pageSize) {
        var page = core.profiles(at.cursor() == null ? null : UUID.fromString(at.cursor()), pageSize);
        requireProgress(at.cursor(), page.next() == null ? null : page.next().toString(), page.items().isEmpty());
        // One transaction per page: the items and the position that covers them commit together.
        return transactions.inTransaction(() -> {
            long rejected = 0;
            for (SnapshotPages.ProfileItem item : page.items()) {
                try { profiles.apply(item.profileId(), item.accountId(), item.version(), item.deleted(), clock.instant()); }
                catch (EventRejection conflict) { rejected++; }
            }
            return advance(generation, BootstrapSource.PROFILES, at, page.next() == null ? null : page.next().toString(),
                    page.items().size(), rejected);
        });
    }

    private BootstrapProgress.Position catalog(long generation, BootstrapProgress.Position at, int pageSize) {
        var page = core.catalog(at.cursor() == null ? null : UUID.fromString(at.cursor()), pageSize);
        requireProgress(at.cursor(), page.next() == null ? null : page.next().toString(), page.items().isEmpty());
        return transactions.inTransaction(() -> {
            long rejected = 0;
            for (CatalogNode node : page.items()) {
                try { catalog.apply(node); }
                catch (EventRejection conflict) { rejected++; }
            }
            return advance(generation, BootstrapSource.CATALOG, at, page.next() == null ? null : page.next().toString(),
                    page.items().size(), rejected);
        });
    }

    private BootstrapProgress.Position playback(long generation, BootstrapProgress.Position at, int pageSize) {
        var page = core.playback(at.cursor(), pageSize);
        requireProgress(at.cursor(), page.next(), page.events().isEmpty());
        long rejected = 0;
        // Each event commits on its own, exactly as a live record would; the receipt makes a repeat a no-op.
        for (SnapshotPages.ReplayedEvent event : page.events()) {
            try { intake.accept(InputTopic.PLAYBACK, event.key(), event.json()); }
            catch (EventRejection never) { rejected++; }
        }
        long pageRejected = rejected;
        return transactions.inTransaction(() -> advance(generation, BootstrapSource.PLAYBACK, at, page.next(),
                page.events().size(), pageRejected));
    }

    private BootstrapProgress.Position advance(long generation, BootstrapSource source, BootstrapProgress.Position at,
            String next, long items, long rejected) {
        var moved = new BootstrapProgress.Position(next == null ? at.cursor() : next, at.applied() + items - rejected,
                at.rejected() + rejected, next == null);
        progress.advance(generation, source, moved.cursor(), moved.applied(), moved.rejected(), moved.completed(),
                clock.instant());
        return moved;
    }

    /** A page that names a further page must move the cursor; otherwise the run would loop on one page. */
    private static void requireProgress(String cursor, String next, boolean empty) {
        if (next != null && (empty || Objects.equals(cursor, next))) {
            throw new CoreSnapshots.SnapshotUnavailableException("PAGE_WITHOUT_PROGRESS");
        }
    }
}
