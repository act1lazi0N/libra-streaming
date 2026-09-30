package com.libra.streaming.media.processing.application;

import com.libra.streaming.media.processing.domain.JobLease;
import com.libra.streaming.media.processing.domain.ProcessingFailure;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** M15 has no success/READY outcome: real source and transcode stages are delivered later. */
@FunctionalInterface
public interface MediaJobHandler {
    Outcome process(JobLease lease, BooleanSupplier cancelled) throws InterruptedException;

    record Outcome(boolean permanent, ProcessingFailure failure) {
        public Outcome { Objects.requireNonNull(failure); }
        public static Outcome retry(ProcessingFailure failure) { return new Outcome(!failure.retryable(), failure); }
        public static Outcome reject(ProcessingFailure failure) { return new Outcome(true, failure); }
    }
}
