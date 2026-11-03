package com.libra.streaming.media.processing.application;

import com.libra.streaming.media.processing.domain.JobLease;
import com.libra.streaming.media.processing.domain.ProcessingFailure;
import com.libra.streaming.media.processing.domain.SourceMetadata;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * Freezes the source, then proves its streams against {@link SourcePolicy} and records the validated metadata
 * under the current lease. It reads only the frozen private object, never staging, and never starts a transcode:
 * a rejected source ends here, and an accepted one is only "probed", not ready or playable.
 */
public final class SourceProber {
    private final SourceFreezer freezer;
    private final JobSources sources;
    private final SourceReader reader;
    private final MediaProber prober;
    private final SourceMetadataStore metadata;

    public SourceProber(SourceFreezer freezer, JobSources sources, SourceReader reader, MediaProber prober,
            SourceMetadataStore metadata) {
        this.freezer = Objects.requireNonNull(freezer);
        this.sources = Objects.requireNonNull(sources);
        this.reader = Objects.requireNonNull(reader);
        this.prober = Objects.requireNonNull(prober);
        this.metadata = Objects.requireNonNull(metadata);
    }

    public Result probe(JobLease lease, BooleanSupplier cancelled) throws InterruptedException {
        var frozen = freezer.freeze(lease, cancelled);
        if (frozen instanceof SourceFreezer.Result.LeaseLost) { return new Result.LeaseLost(); }
        if (frozen instanceof SourceFreezer.Result.Rejected rejected) { return new Result.Rejected(rejected.outcome()); }
        return inspect(lease, ((SourceFreezer.Result.Frozen) frozen).key(), cancelled);
    }

    private Result inspect(JobLease lease, String key, BooleanSupplier cancelled) throws InterruptedException {
        var target = sources.target(lease).orElse(null);
        if (target == null) { return new Result.LeaseLost(); }
        // A retry after a committed probe reuses the recorded facts: the frozen bytes cannot have changed.
        var recorded = metadata.find(lease).orElse(null);
        if (recorded != null) { return new Result.Probed(recorded); }
        try (var copy = reader.open(key, target.byteLength(), cancelled).orElse(null)) {
            if (copy == null) { return reject(ProcessingFailure.SOURCE_MISSING); }
            // The probe describes exactly the proven bytes; the file is re-measured because it was just read again.
            if (copy.bytes() != target.byteLength()) { return reject(ProcessingFailure.SIZE_MISMATCH); }
            if (!target.sha256().equals(copy.sha256())) { return reject(ProcessingFailure.CHECKSUM_MISMATCH); }
            if (cancelled.getAsBoolean()) { throw new InterruptedException(); }
            ProbeReport report;
            try {
                report = prober.probe(copy.file(), cancelled);
            } catch (MediaProber.Unreadable unreadable) {
                return reject(ProcessingFailure.CORRUPT_INPUT);
            }
            return switch (SourcePolicy.evaluate(report)) {
                case SourcePolicy.Verdict.Rejected rejected -> reject(rejected.failure());
                case SourcePolicy.Verdict.Accepted accepted ->
                        metadata.record(lease, key, accepted.metadata()) ? new Result.Probed(accepted.metadata())
                                : new Result.LeaseLost();
            };
        } catch (SourceStorage.Unavailable | MediaProber.Unavailable unavailable) {
            return new Result.Rejected(MediaJobHandler.Outcome.retry(ProcessingFailure.PROCESSING_FAILED));
        }
    }

    private static Result reject(ProcessingFailure failure) {
        return new Result.Rejected(MediaJobHandler.Outcome.reject(failure));
    }

    public sealed interface Result {
        /** The source is frozen, verified and acceptable; its validated metadata is committed. */
        record Probed(SourceMetadata metadata) implements Result {}
        /** Input rejection (permanent) or a retryable fault, in the worker's existing outcome vocabulary. */
        record Rejected(MediaJobHandler.Outcome outcome) implements Result {}
        /** The lease was no longer current; the caller must not record any outcome. */
        record LeaseLost() implements Result {}
    }
}
