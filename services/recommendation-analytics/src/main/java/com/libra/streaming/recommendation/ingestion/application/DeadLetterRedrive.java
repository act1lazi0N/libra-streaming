package com.libra.streaming.recommendation.ingestion.application;

import com.libra.streaming.recommendation.ingestion.domain.DeadLetter.Held;
import com.libra.streaming.recommendation.ingestion.domain.EventRejection;
import java.time.Clock;
import java.util.function.Predicate;

/**
 * Feeds parked records back through the intake with their original key and body, so they keep their event identity:
 * a record that was applied meanwhile (for example by a bootstrap replay) is a duplicate and adds nothing. One pass
 * visits every HELD row once, in (receivedAt, id) order; a row that is still rejected stays HELD with its attempt
 * counted. A transient failure (an outage) stops the pass and leaves the remaining rows HELD; any other failure is
 * recorded on its row as {@code REDRIVE_FAILED} and the pass goes on, so one broken row cannot hide the rows after it.
 */
public class DeadLetterRedrive {
    public record Report(int redriven, int stillHeld) {}

    private final DeadLetters letters;
    private final EventIntake intake;
    private final Transactions transactions;
    private final Clock clock;
    private final Predicate<RuntimeException> transientFailure;

    /** @param transientFailure true for an outage that a later pass may get past (infrastructure classifies it) */
    public DeadLetterRedrive(DeadLetters letters, EventIntake intake, Transactions transactions, Clock clock,
            Predicate<RuntimeException> transientFailure) {
        this.letters = letters;
        this.intake = intake;
        this.transactions = transactions;
        this.clock = clock;
        this.transientFailure = transientFailure;
    }

    public Report redriveHeld(int pageSize) {
        if (pageSize < 1) { throw new IllegalArgumentException("pageSize"); }
        int redriven = 0;
        Held after = null;
        while (true) {
            // Keyset paging: rejected rows stay HELD but lie before the cursor, so later rows are always reached.
            Held cursor = after;
            var page = transactions.inTransaction(() -> letters.held(cursor, pageSize));
            for (Held row : page) {
                try { intake.accept(row.topic(), row.key(), row.body()); }
                catch (EventRejection rejection) {
                    failed(row, rejection.reason() + ":" + rejection.code());
                    continue;
                } catch (RuntimeException failure) {
                    if (transientFailure.test(failure)) { throw failure; }
                    failed(row, "REDRIVE_FAILED");
                    continue;
                }
                // After the intake committed: a crash here leaves the row HELD and the next pass sees a duplicate.
                transactions.inTransaction(() -> { letters.redriven(row.id(), clock.instant()); return null; });
                redriven++;
            }
            if (page.size() < pageSize) { break; }
            after = page.getLast();
        }
        return new Report(redriven, transactions.inTransaction(letters::countHeld));
    }

    private void failed(Held row, String code) {
        transactions.inTransaction(() -> { letters.attemptFailed(row.id(), code); return null; });
    }
}
