package com.libra.streaming.recommendation.bootstrap.application;

import com.libra.streaming.recommendation.bootstrap.domain.BootstrapSource;
import com.libra.streaming.recommendation.bootstrap.domain.SnapshotPages.*;
import com.libra.streaming.recommendation.catalog.application.CatalogProjection;
import com.libra.streaming.recommendation.catalog.application.CatalogStore;
import com.libra.streaming.recommendation.catalog.domain.CatalogNode;
import com.libra.streaming.recommendation.freshness.domain.InputTopic;
import com.libra.streaming.recommendation.ingestion.application.EventIntake;
import com.libra.streaming.recommendation.ingestion.application.Transactions;
import com.libra.streaming.recommendation.ingestion.domain.CatalogChange;
import com.libra.streaming.recommendation.ingestion.domain.EventRejection;
import com.libra.streaming.recommendation.profiles.application.ProfileProjection;
import com.libra.streaming.recommendation.profiles.application.ProfileStore;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.assertj.core.api.Assertions.*;

class BootstrapTest {
    static final UUID FIRST = new UUID(0, 1), SECOND = new UUID(0, 2), ACCOUNT = new UUID(0, 3);
    static final Instant NOW = Instant.parse("2026-11-16T00:00:00Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void appliesSourcesAndPagesInOrderSavingEveryPageInItsTransaction() {
        var f = new Fixture();
        var report = f.bootstrap.run(1, 1);
        assertThat(report.completed()).isTrue();
        assertThat(f.bootstrap.completed(1)).isTrue();
        assertThat(f.operations).containsExactly("profile:1", "save:PROFILES:1:0:false", "profile:2",
                "save:PROFILES:2:0:true", "catalog:1", "save:CATALOG:1:0:false", "catalog:2",
                "save:CATALOG:2:0:true", "playback:one", "save:PLAYBACK:1:0:false", "playback:two",
                "save:PLAYBACK:2:0:true");
        assertThat(f.saved).hasSize(6);
        assertThat(f.saved.getFirst()).isEqualTo(new BootstrapProgress.Position(FIRST.toString(), 1, 0, false));
        assertThat(f.saved.get(1)).isEqualTo(new BootstrapProgress.Position(FIRST.toString(), 2, 0, true));
        assertThat(f.calls).containsExactly("PROFILES:null", "PROFILES:" + FIRST, "CATALOG:null", "CATALOG:" + FIRST,
                "PLAYBACK:null", "PLAYBACK:cursor-one");
        assertThat(report.sources().values()).allSatisfy(p -> assertThat(p.applied()).isEqualTo(2));
    }

    @Test
    void resumesAtPageTwoAfterFailureWithoutRepeatingTheCommittedPage() {
        var f = new Fixture();
        f.failSecondProfilePage = true;
        assertThatThrownBy(() -> f.bootstrap.run(1, 1)).hasMessage("CORE_TRANSPORT");
        assertThat(f.progress.find(1, BootstrapSource.PROFILES)).contains(
                new BootstrapProgress.Position(FIRST.toString(), 1, 0, false));
        assertThat(f.saved).hasSize(1);
        assertThat(f.bootstrap.completed(1)).isFalse();
        assertThat(f.bootstrap.run(1, 1).completed()).isTrue();
        assertThat(f.calls.subList(0, 3)).containsExactly("PROFILES:null", "PROFILES:" + FIRST, "PROFILES:" + FIRST);
        assertThat(Collections.frequency(f.operations, "profile:1")).isEqualTo(1);
        assertThat(Collections.frequency(f.operations, "profile:2")).isEqualTo(1);
    }

    @Test
    void skipsCompletedSourcesAndCompletedRuns() {
        var f = new Fixture();
        f.positions.put("1:PROFILES", new BootstrapProgress.Position("finished", 17, 2, true));
        var report = f.bootstrap.run(1, 1);
        assertThat(f.calls).noneMatch(call -> call.startsWith("PROFILES:"));
        assertThat(report.sources().get(BootstrapSource.PROFILES).applied()).isEqualTo(17);
        int calls = f.calls.size();
        int saves = f.saved.size();
        f.bootstrap.run(1, 1);
        assertThat(f.calls).hasSize(calls);
        assertThat(f.saved).hasSize(saves);
    }

    @Test
    void countsProjectionConflictsAndPlaybackRejectionsThenContinues() {
        var f = new Fixture();
        f.rejectFirst = true;
        var report = f.bootstrap.run(1, 1);
        assertThat(report.completed()).isTrue();
        assertThat(report.sources().values()).allSatisfy(p -> {
            assertThat(p.applied()).isEqualTo(1);
            assertThat(p.rejected()).isEqualTo(1);
        });
        assertThat(f.operations).contains("profile:2", "catalog:2", "playback:two");
    }

    @ParameterizedTest
    @EnumSource(BootstrapSource.class)
    void rejectsNextWithNoItemsForEverySource(BootstrapSource source) {
        var f = new Fixture();
        f.invalidSource = source;
        f.emptyInvalidPage = true;
        assertThatThrownBy(() -> f.bootstrap.run(1, 1)).isInstanceOf(CoreSnapshots.SnapshotUnavailableException.class)
                .hasMessage("PAGE_WITHOUT_PROGRESS");
        assertThat(f.progress.find(1, source)).contains(new BootstrapProgress.Position(null, 0, 0, false));
    }

    @ParameterizedTest
    @EnumSource(BootstrapSource.class)
    void rejectsNextEqualToStoredCursorForEverySource(BootstrapSource source) {
        var f = new Fixture();
        f.invalidSource = source;
        String cursor = source == BootstrapSource.PLAYBACK ? "cursor-one" : FIRST.toString();
        var position = new BootstrapProgress.Position(cursor, 1, 0, false);
        f.positions.put("1:" + source, position);
        assertThatThrownBy(() -> f.bootstrap.run(1, 1)).hasMessage("PAGE_WITHOUT_PROGRESS");
        assertThat(f.progress.find(1, source)).contains(position);
    }

    @Test
    void aNewGenerationStartsAllSourcesAgain() {
        var f = new Fixture();
        f.bootstrap.run(1, 1);
        assertThat(f.bootstrap.completed(2)).isFalse();
        assertThat(f.bootstrap.run(2, 1).completed()).isTrue();
        for (BootstrapSource source : BootstrapSource.values()) {
            assertThat(Collections.frequency(f.calls, source + ":null")).isEqualTo(2);
            assertThat(f.progress.find(1, source)).isEqualTo(f.progress.find(2, source));
        }
    }

    static CatalogNode node(UUID id) {
        return new CatalogNode(id, 1, CatalogChange.Kind.MOVIE, null, null, null, null, null, false, NOW);
    }

    /** Plain fakes record observable effects and require each position write to join a transaction. */
    static final class Fixture {
        final Map<String, BootstrapProgress.Position> positions = new HashMap<>();
        final List<BootstrapProgress.Position> saved = new ArrayList<>();
        final List<String> calls = new ArrayList<>(), operations = new ArrayList<>();
        int transactionDepth;
        boolean failSecondProfilePage, rejectFirst, emptyInvalidPage;
        BootstrapSource invalidSource;
        final Transactions transactions = new Transactions() {
            @Override public <T> T inTransaction(Supplier<T> work) {
                transactionDepth++;
                try { return work.get(); } finally { transactionDepth--; }
            }
        };
        final BootstrapProgress progress = new BootstrapProgress() {
            @Override public Position start(long gen, BootstrapSource source, Instant at) {
                assertThat(transactionDepth).isPositive();
                assertThat(at).isEqualTo(NOW);
                return positions.computeIfAbsent(gen + ":" + source, ignored -> new Position(null, 0, 0, false));
            }
            @Override public void advance(long gen, BootstrapSource source, String cursor, long applied,
                    long rejected, boolean completed, Instant at) {
                assertThat(transactionDepth).isPositive();
                assertThat(at).isEqualTo(NOW);
                var position = new Position(cursor, applied, rejected, completed);
                positions.put(gen + ":" + source, position);
                saved.add(position);
                operations.add("save:" + source + ":" + applied + ":" + rejected + ":" + completed);
            }
            @Override public Optional<Position> find(long gen, BootstrapSource source) {
                return Optional.ofNullable(positions.get(gen + ":" + source));
            }
        };
        final CoreSnapshots snapshots = new CoreSnapshots() {
            @Override public ProfilePage profiles(UUID after, int limit) {
                fetched(BootstrapSource.PROFILES, after);
                if (after != null && failSecondProfilePage) {
                    failSecondProfilePage = false;
                    throw new SnapshotUnavailableException("CORE_TRANSPORT");
                }
                boolean invalid = invalidSource == BootstrapSource.PROFILES;
                return new ProfilePage(invalid && emptyInvalidPage ? List.of()
                        : List.of(new ProfileItem(after == null ? FIRST : SECOND, ACCOUNT, 1, false)),
                        invalid || after == null ? FIRST : null);
            }
            @Override public CatalogPage catalog(UUID after, int limit) {
                fetched(BootstrapSource.CATALOG, after);
                boolean invalid = invalidSource == BootstrapSource.CATALOG;
                return new CatalogPage(invalid && emptyInvalidPage ? List.of() : List.of(node(after == null ? FIRST : SECOND)),
                        invalid || after == null ? FIRST : null);
            }
            @Override public PlaybackPage playback(String after, int limit) {
                fetched(BootstrapSource.PLAYBACK, after);
                boolean invalid = invalidSource == BootstrapSource.PLAYBACK;
                return new PlaybackPage(invalid && emptyInvalidPage ? List.of()
                        : List.of(new ReplayedEvent("session", after == null ? "one" : "two")),
                        invalid || after == null ? "cursor-one" : null);
            }
        };
        final ProfileProjection profiles = new ProfileProjection(null, List.of()) {
            @Override public ProfileStore.Write apply(UUID id, UUID account, long version, boolean deleted, Instant at) {
                assertThat(transactionDepth).isPositive();
                assertThat(account).isEqualTo(ACCOUNT);
                operations.add("profile:" + id.getLeastSignificantBits());
                if (rejectFirst && id.equals(FIRST)) { throw EventRejection.conflict("PROFILE_ACCOUNT_CHANGED"); }
                return ProfileStore.Write.APPLIED;
            }
        };
        final CatalogProjection catalog = new CatalogProjection(null) {
            @Override public CatalogStore.Write apply(CatalogNode node) {
                assertThat(transactionDepth).isPositive();
                operations.add("catalog:" + node.contentId().getLeastSignificantBits());
                if (rejectFirst && node.contentId().equals(FIRST)) { throw EventRejection.conflict("TREE_POSITION"); }
                return CatalogStore.Write.APPLIED;
            }
        };
        final EventIntake intake = new EventIntake(null, null, null, null, null, null, null) {
            @Override public Outcome accept(InputTopic topic, String key, String json) {
                assertThat(topic).isEqualTo(InputTopic.PLAYBACK);
                assertThat(key).isEqualTo("session");
                operations.add("playback:" + json);
                if (rejectFirst && json.equals("one")) { throw EventRejection.schema("INVALID_EVENT"); }
                return Outcome.APPLIED;
            }
        };
        final Bootstrap bootstrap = new Bootstrap(snapshots, progress, transactions, profiles, catalog, intake, CLOCK);

        void fetched(BootstrapSource source, Object after) {
            assertThat(transactionDepth).isZero();
            calls.add(source + ":" + after);
            if (after != null && invalidSource != source) {
                assertThat(positions.values()).anySatisfy(p -> assertThat(p.cursor()).isEqualTo(after.toString()));
            }
        }
    }
}
