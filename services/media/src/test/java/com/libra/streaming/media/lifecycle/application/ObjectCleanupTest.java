package com.libra.streaming.media.lifecycle.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.libra.streaming.media.lifecycle.domain.CleanupPlan;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ObjectCleanupTest {
    final UUID upload = UUID.randomUUID();
    final Storage storage = new Storage();
    final Plans plans = new Plans();
    final Events events = new Events();

    @Test
    void deletesEveryUnselectedAttemptAndNeverNamesTheSelectedOnes() {
        storage.objects.addAll(List.of("staging/" + upload + "/source.mp4", source(1), source(2), source(3),
                output(1) + "/a.ts", output(2) + "/a.ts", output(2) + "/master.m3u8", output(3) + "/a.ts"));
        var plan = new CleanupPlan(upload, "staging/" + upload + "/source.mp4", 3, source(2), output(2));
        var cleanup = new ObjectCleanup(storage, plans, events, 100);

        assertThat(cleanup.clean(plan)).isEqualTo(5);
        assertThat(storage.objects).containsExactlyInAnyOrder(source(2), output(2) + "/a.ts",
                output(2) + "/master.m3u8");
        assertThat(storage.touched).doesNotContain(source(2), output(2));
        assertThat(plans.cleaned).isEmpty();
        assertThat(events.deleted).isEqualTo(5);

        assertThat(cleanup.clean(plan)).isZero();
        assertThat(plans.cleaned).containsExactly(upload);
        assertThat(events.cleaned).isEqualTo(1);
        assertThat(storage.objects).hasSize(3);
    }

    @Test
    void anUploadWithoutAttemptsOnlyLosesItsStaging() {
        storage.objects.add("staging/" + upload + "/source.mp4");
        var cleanup = new ObjectCleanup(storage, plans, events, 100);
        var plan = new CleanupPlan(upload, "staging/" + upload + "/source.mp4", 0, null, null);
        assertThat(cleanup.clean(plan)).isEqualTo(1);
        assertThat(cleanup.clean(plan)).isZero();
        assertThat(storage.touched).containsOnly("staging/" + upload + "/source.mp4");
        assertThat(plans.cleaned).containsExactly(upload);
    }

    @Test
    void aBoundedPrefixThatStillHoldsObjectsIsNotRecordedAsCleaned() {
        for (int i = 0; i < 5; i++) { storage.objects.add(output(1) + "/s" + i + ".ts"); }
        var cleanup = new ObjectCleanup(storage, plans, events, 2);
        var plan = new CleanupPlan(upload, "staging/" + upload + "/source.mp4", 1, null, null);
        assertThat(cleanup.clean(plan)).isEqualTo(2);
        assertThat(cleanup.clean(plan)).isEqualTo(2);
        assertThat(cleanup.clean(plan)).isEqualTo(1);
        assertThat(plans.cleaned).isEmpty();
        assertThat(cleanup.clean(plan)).isZero();
        assertThat(plans.cleaned).containsExactly(upload);
    }

    @Test
    void aSelectedOutputOverlappingAnAttemptPrefixIsNeverDeleted() {
        storage.objects.add(output(1) + "/selected/master.m3u8");
        var cleanup = new ObjectCleanup(storage, plans, events, 100);
        // A selection that is not an attempt prefix itself (a bug elsewhere) still protects what lies under it.
        cleanup.clean(new CleanupPlan(upload, "staging/" + upload + "/source.mp4", 1, null, output(1) + "/selected"));
        cleanup.clean(new CleanupPlan(upload, "staging/" + upload + "/source.mp4", 1, null, "hls/" + upload));
        assertThat(storage.objects).containsExactly(output(1) + "/selected/master.m3u8");
    }

    @Test
    void aStorageFailureLeavesTheUploadForTheNextPass() {
        storage.down = true;
        var cleanup = new ObjectCleanup(storage, plans, events, 100);
        assertThatThrownBy(() -> cleanup.clean(new CleanupPlan(upload, "staging/" + upload + "/source.mp4", 1,
                null, null))).isInstanceOf(CleanupStorage.Unavailable.class);
        assertThat(plans.cleaned).isEmpty();
    }

    @Test
    void sweeperKeepsCleaningAfterAnExpiryFailureAndStopsOnStorageFailure() {
        var expiry = new UploadExpiry() {
            public int expire(int limit) { throw new IllegalStateException("database down"); }
        };
        var other = UUID.randomUUID();
        plans.due = List.of(new CleanupPlan(upload, "staging/" + upload + "/source.mp4", 0, null, null),
                new CleanupPlan(other, "staging/" + other + "/source.mp4", 0, null, null));
        try (var sweeper = new LifecycleSweeper(expiry, plans, new ObjectCleanup(storage, plans, events, 100), events,
                settings())) {
            sweeper.pass();
            assertThat(events.failures).containsExactly("EXPIRY_UNAVAILABLE");
            assertThat(plans.cleaned).containsExactly(upload, other);
            storage.down = true;
            plans.cleaned.clear();
            sweeper.pass();
            assertThat(events.failures).containsExactly("EXPIRY_UNAVAILABLE", "EXPIRY_UNAVAILABLE",
                    "CLEANUP_STORAGE_UNAVAILABLE");
            assertThat(storage.touched).hasSize(2);
        }
    }

    @Test
    void sweeperReportsAnUnreadablePlanWithoutDeletingAnything() {
        var failing = new Plans() {
            @Override public List<CleanupPlan> due(Duration grace, int limit) { throw new IllegalStateException(); }
        };
        try (var sweeper = new LifecycleSweeper(limit -> 0, failing,
                new ObjectCleanup(storage, failing, events, 100), events, settings())) {
            sweeper.pass();
        }
        assertThat(events.failures).containsExactly("CLEANUP_DATABASE_UNAVAILABLE");
        assertThat(storage.touched).isEmpty();
    }

    @Test
    void settingsRefuseAGraceShorterThanAdmittedWrites() {
        assertThatThrownBy(() -> new LifecycleSweeper.Settings(Duration.ofSeconds(1), 20, Duration.ofMinutes(4),
                Duration.ofSeconds(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LifecycleSweeper.Settings(Duration.ofSeconds(1), 0, Duration.ofHours(1),
                Duration.ofSeconds(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LifecycleSweeper.Settings(Duration.ofMillis(10), 20, Duration.ofHours(1),
                Duration.ofSeconds(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ObjectCleanup(storage, plans, events, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CleanupPlan(upload, "staging/x", 4, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static LifecycleSweeper.Settings settings() {
        return new LifecycleSweeper.Settings(Duration.ofSeconds(1), 20, Duration.ofHours(1), Duration.ofSeconds(1));
    }

    private String source(int attempt) { return "sources/" + upload + "/attempt-" + attempt + "/source.mp4"; }
    private String output(int attempt) { return "hls/" + upload + "/attempt-" + attempt; }

    static class Storage implements CleanupStorage {
        final Set<String> objects = new HashSet<>();
        final List<String> touched = new ArrayList<>();
        boolean down;

        @Override public String sourceKey(UUID uploadId, int attempt) {
            return "sources/" + uploadId + "/attempt-" + attempt + "/source.mp4";
        }
        @Override public String outputPrefix(UUID uploadId, int attempt) {
            return "hls/" + uploadId + "/attempt-" + attempt;
        }
        @Override public boolean deleteStaging(String key) { return delete(key); }
        @Override public boolean deleteSource(String key) { return delete(key); }
        @Override public Batch deleteOutput(String prefix, int limit) {
            if (down) { throw new Unavailable(); }
            touched.add(prefix);
            var under = objects.stream().filter(key -> key.startsWith(prefix + "/")).sorted().toList();
            var batch = under.subList(0, Math.min(limit, under.size()));
            batch.forEach(objects::remove);
            return new Batch(batch.size(), batch.size() == under.size());
        }
        private boolean delete(String key) {
            if (down) { throw new Unavailable(); }
            touched.add(key);
            return objects.remove(key);
        }
    }

    static class Plans implements CleanupPlans {
        final List<UUID> cleaned = new ArrayList<>();
        List<CleanupPlan> due = List.of();

        @Override public List<CleanupPlan> due(Duration grace, int limit) { return due; }
        @Override public boolean markCleaned(UUID uploadId) {
            if (cleaned.contains(uploadId)) { return false; }
            return cleaned.add(uploadId);
        }
    }

    static class Events implements LifecycleEvents {
        int deleted;
        int cleaned;
        final List<String> failures = new ArrayList<>();

        @Override public void expired(int uploads) {}
        @Override public void deleted(int objects) { deleted += objects; }
        @Override public void cleaned() { cleaned++; }
        @Override public void failed(String code) { failures.add(code); }
    }
}
