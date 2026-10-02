package com.libra.streaming.media.processing.application;

import com.libra.streaming.media.processing.domain.JobLease;
import com.libra.streaming.media.processing.domain.ProcessingFailure;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * Copies the mutable staging object once, proves its bytes, and selects a private immutable copy.
 * After a selection commits, staging is never read again for that asset.
 */
public final class SourceFreezer {
    private final JobSources sources;
    private final SourceStorage storage;

    public SourceFreezer(JobSources sources, SourceStorage storage) {
        this.sources = Objects.requireNonNull(sources);
        this.storage = Objects.requireNonNull(storage);
    }

    public Result freeze(JobLease lease, BooleanSupplier cancelled) throws InterruptedException {
        var target = sources.target(lease).orElse(null);
        if (target == null) { return new Result.LeaseLost(); }
        try {
            return target.selectedKey() == null ? freezeStaging(lease, target, cancelled)
                    : reuse(lease, target, cancelled);
        } catch (SourceStorage.Unavailable unavailable) {
            return new Result.Rejected(MediaJobHandler.Outcome.retry(ProcessingFailure.PROCESSING_FAILED));
        }
    }

    private Result reuse(JobLease lease, JobSources.Target target, BooleanSupplier cancelled)
            throws InterruptedException {
        var stored = storage.digest(target.selectedKey(), target.byteLength(), cancelled).orElse(null);
        if (stored == null) { return reject(ProcessingFailure.SOURCE_MISSING); }
        var mismatch = mismatch(target, stored.bytes(), stored.sha256());
        // The selected object is the only source; staging is deliberately not consulted as a fallback.
        if (mismatch != null) { return reject(mismatch); }
        return sources.select(lease, target.selectedKey()) ? new Result.Frozen(target.selectedKey())
                : new Result.LeaseLost();
    }

    private Result freezeStaging(JobLease lease, JobSources.Target target, BooleanSupplier cancelled)
            throws InterruptedException {
        try (var copy = storage.stage(target.stagingKey(), target.byteLength(), cancelled).orElse(null)) {
            if (copy == null) { return reject(ProcessingFailure.SOURCE_MISSING); }
            var mismatch = mismatch(target, copy.bytes(), copy.sha256());
            if (mismatch != null) { return reject(mismatch); }
            if (cancelled.getAsBoolean()) { throw new InterruptedException(); }
            String key = storage.frozenKey(lease);
            storage.store(key, copy);
            var stored = storage.digest(key, target.byteLength(), cancelled).orElse(null);
            // The input was already proven; a different readback is a storage fault, so a new attempt may retry.
            if (stored == null || mismatch(target, stored.bytes(), stored.sha256()) != null) {
                return new Result.Rejected(MediaJobHandler.Outcome.retry(ProcessingFailure.PROCESSING_FAILED));
            }
            // A late or expired attempt may leave the object behind, but cannot select it.
            return sources.select(lease, key) ? new Result.Frozen(key) : new Result.LeaseLost();
        }
    }

    private static ProcessingFailure mismatch(JobSources.Target target, long bytes, String sha256) {
        if (bytes != target.byteLength()) { return ProcessingFailure.SIZE_MISMATCH; }
        return target.sha256().equals(sha256) ? null : ProcessingFailure.CHECKSUM_MISMATCH;
    }

    private static Result reject(ProcessingFailure failure) {
        return new Result.Rejected(MediaJobHandler.Outcome.reject(failure));
    }

    public sealed interface Result {
        /** The verified, committed source for this asset. */
        record Frozen(String key) implements Result {}
        /** Input rejection (permanent) or a retryable fault, in the worker's existing outcome vocabulary. */
        record Rejected(MediaJobHandler.Outcome outcome) implements Result {}
        /** The lease was no longer current; the caller must not record any outcome. */
        record LeaseLost() implements Result {}
    }
}
