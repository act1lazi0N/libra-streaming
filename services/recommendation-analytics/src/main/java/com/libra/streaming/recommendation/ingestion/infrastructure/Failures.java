package com.libra.streaming.recommendation.ingestion.infrastructure;

import com.libra.streaming.recommendation.ingestion.domain.EventRejection;
import java.sql.SQLException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientException;
import java.util.Optional;
import java.util.function.Predicate;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.transaction.CannotCreateTransactionException;

/**
 * Classifies why a record failed. Three classes, three policies (docs/analytics-dead-letters-ra02-3.md):
 * a rejection is parked at once; a transient failure (database or network outage, lock conflict) is retried with
 * capped back-off and is never parked, so an outage cannot reorder or lose events; anything else is retried a
 * bounded number of times, then parked as RETRY_EXHAUSTED.
 */
public final class Failures {
    private Failures() {}

    /** Thrown in place of a transient cause, so Kafka error handling never logs SQL or driver text. */
    public static final class TransientIntakeException extends RuntimeException {
        public TransientIntakeException() { super("Transient Analytics intake failure", null, false, false); }
    }

    /** Thrown in place of an unclassified cause, for the same reason. */
    public static final class IntakeFailedException extends RuntimeException {
        public IntakeFailedException() { super("Analytics intake failed", null, false, false); }
    }

    public static Optional<EventRejection> rejection(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof EventRejection rejection) { return Optional.of(rejection); }
            if (cause.getCause() == cause) { break; }
        }
        return Optional.empty();
    }

    public static boolean isTransient(Throwable failure) {
        if (anyCause(failure, cause -> cause instanceof TransientIntakeException)) { return true; }
        // Class 54 (program limit exceeded) is reported as a resource failure too, but retrying cannot fix it.
        if (anyCause(failure, cause -> cause instanceof SQLException sql && sql.getSQLState() != null
                && sql.getSQLState().startsWith("54"))) { return false; }
        return anyCause(failure, cause -> {
            if (cause instanceof SQLException sql && sql.getSQLState() != null) {
                String state = sql.getSQLState();
                // Connection exceptions, insufficient resources, operator intervention (shutdown), serialization
                // failure, deadlock and lock-not-available.
                if (state.startsWith("08") || state.startsWith("53") || state.startsWith("57P0")
                        || state.equals("40001") || state.equals("40P01") || state.equals("55P03")) { return true; }
            }
            return cause instanceof TransientDataAccessException || cause instanceof RecoverableDataAccessException
                    || cause instanceof DataAccessResourceFailureException
                    || cause instanceof CannotCreateTransactionException
                    || cause instanceof SQLTransientException || cause instanceof SQLRecoverableException;
        });
    }

    static boolean anyCause(Throwable failure, Predicate<Throwable> test) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (test.test(cause)) { return true; }
            if (cause.getCause() == cause) { break; }
        }
        return false;
    }
}
