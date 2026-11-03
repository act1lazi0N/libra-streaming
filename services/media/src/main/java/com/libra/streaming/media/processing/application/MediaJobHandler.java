package com.libra.streaming.media.processing.application;

import com.libra.streaming.media.processing.domain.JobLease;
import com.libra.streaming.media.processing.domain.ProcessingFailure;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * Processes one claimed job. A failure outcome is recorded by the worker under the lease; a {@link Outcome#settled()}
 * outcome means the handler itself already committed the job's terminal success (READY) under the lease, or lost the
 * authority to record anything, so the worker records nothing.
 */
@FunctionalInterface
public interface MediaJobHandler {
    Outcome process(JobLease lease, BooleanSupplier cancelled) throws InterruptedException;

    /** A failure to record, or {@code failure == null} for a job the handler settled itself. */
    record Outcome(boolean permanent, ProcessingFailure failure) {
        private static final Outcome SETTLED = new Outcome(false, null);

        public Outcome {
            if (failure == null && permanent) { throw new IllegalArgumentException("Permanent without failure"); }
        }
        public static Outcome retry(ProcessingFailure failure) {
            return new Outcome(!Objects.requireNonNull(failure).retryable(), failure);
        }
        public static Outcome reject(ProcessingFailure failure) {
            return new Outcome(true, Objects.requireNonNull(failure));
        }
        /** Nothing for the worker to record: READY is committed, or the lease is no longer this attempt's. */
        public static Outcome settled() { return SETTLED; }
        public boolean isSettled() { return failure == null; }
    }
}
