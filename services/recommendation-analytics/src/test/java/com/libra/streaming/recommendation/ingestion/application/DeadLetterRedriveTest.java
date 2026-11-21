package com.libra.streaming.recommendation.ingestion.application;

import com.libra.streaming.recommendation.freshness.domain.InputTopic;
import com.libra.streaming.recommendation.ingestion.domain.DeadLetter.*;
import com.libra.streaming.recommendation.ingestion.domain.EventRejection;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DeadLetterRedriveTest {
    static final Instant NOW = Instant.parse("2026-11-16T00:00:00Z");
    static final Transactions TX = new Transactions() {
        public <T> T inTransaction(Supplier<T> work) { return work.get(); }
    };
    final Memory letters = new Memory();
    final EventIntake intake = mock(EventIntake.class);
    final DeadLetterRedrive redrive = new DeadLetterRedrive(letters, intake, TX, Clock.fixed(NOW, ZoneOffset.UTC),
            failure -> failure.getCause() instanceof java.sql.SQLException sql && sql.getSQLState().startsWith("08"));

    @Test
    void successRedrivesAllPagesWithOriginalIdentity() {
        var a = letters.add(); var b = letters.add(); var c = letters.add();
        assertThat(redrive.redriveHeld(1)).isEqualTo(new DeadLetterRedrive.Report(3, 0));
        for (var row : List.of(a, b, c)) {
            verify(intake).accept(row.topic(), row.key(), row.body());
            assertThat(letters.done).containsEntry(row.id(), NOW);
        }
    }

    @Test
    void rejectionRecordsCodeAndContinuesWithoutReattempting() {
        var a = letters.add(); var b = letters.add(); var c = letters.add();
        when(intake.accept(a.topic(), a.key(), a.body())).thenThrow(EventRejection.schema("EMPTY_RECORD"));
        assertThat(redrive.redriveHeld(2)).isEqualTo(new DeadLetterRedrive.Report(2, 1));
        assertThat(letters.failed).containsEntry(a.id(), "SCHEMA_INVALID:EMPTY_RECORD");
        verify(intake, times(1)).accept(a.topic(), a.key(), a.body());
        assertThat(letters.done).containsKeys(b.id(), c.id());
    }

    @Test
    void runtimeFailureStopsPassLeavingRowsHeld() {
        var a = letters.add(); var b = letters.add();
        var outage = new RuntimeException(new java.sql.SQLException("outage", "08006"));
        when(intake.accept(a.topic(), a.key(), a.body())).thenThrow(outage);
        assertThatThrownBy(() -> redrive.redriveHeld(2)).isSameAs(outage);
        assertThat(letters.rows).containsExactly(a, b);
        assertThat(letters.done).isEmpty();
        assertThat(letters.failed).isEmpty();
        verify(intake, never()).accept(b.topic(), b.key(), b.body());
    }

    @Test
    void aFullyRejectedPageDoesNotHideTheRowsAfterIt() {
        // Regression: the first pass stopped after a page without progress, so later rows were never tried.
        var a = letters.add(); var b = letters.add(); var c = letters.add();
        when(intake.accept(a.topic(), a.key(), a.body())).thenThrow(EventRejection.semantic("KEY_NOT_AGGREGATE"));
        assertThat(redrive.redriveHeld(1)).isEqualTo(new DeadLetterRedrive.Report(2, 1));
        assertThat(letters.done).containsOnlyKeys(b.id(), c.id());
        assertThat(letters.failed).containsOnlyKeys(a.id());
        assertThat(redrive.redriveHeld(1)).isEqualTo(new DeadLetterRedrive.Report(0, 1));
        verify(intake, times(2)).accept(a.topic(), a.key(), a.body());
    }

    @Test
    void aDeterministicNonTransientFailureIsRecordedAndDoesNotStopThePass() {
        // Review finding: any non-rejection failure used to end the pass, so one broken row hid every row after it.
        var a = letters.add(); var b = letters.add(); var c = letters.add();
        when(intake.accept(a.topic(), a.key(), a.body())).thenThrow(new IllegalStateException("bug"));
        assertThat(redrive.redriveHeld(1)).isEqualTo(new DeadLetterRedrive.Report(2, 1));
        assertThat(letters.failed).containsEntry(a.id(), "REDRIVE_FAILED");
        assertThat(letters.done).containsOnlyKeys(b.id(), c.id());
    }

    @Test
    void invalidPageSizeIsRejected() {
        assertThatThrownBy(() -> redrive.redriveHeld(0)).isInstanceOf(IllegalArgumentException.class);
    }

    static class Memory implements DeadLetters {
        final List<Held> rows = new ArrayList<>();
        final Map<UUID, Instant> done = new HashMap<>();
        final Map<UUID, String> failed = new HashMap<>();
        Held add() {
            var row = new Held(UUID.randomUUID(), NOW.plusSeconds(rows.size()), InputTopic.PLAYBACK,
                    UUID.randomUUID().toString(), "body");
            rows.add(row); return row;
        }
        public void capture(Captured record) { throw new UnsupportedOperationException(); }
        public List<Held> held(Held after, int limit) {
            return rows.stream().filter(row -> after == null || row.receivedAt().isAfter(after.receivedAt()))
                    .limit(limit).toList();
        }
        public int countHeld() { return rows.size(); }
        public void redriven(UUID id, Instant at) { rows.removeIf(row -> row.id().equals(id)); done.put(id, at); }
        public void attemptFailed(UUID id, String code) { failed.put(id, code); }
    }
}
