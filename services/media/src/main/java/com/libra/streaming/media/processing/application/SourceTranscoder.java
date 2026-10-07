package com.libra.streaming.media.processing.application;

import com.libra.streaming.media.processing.domain.JobLease;
import com.libra.streaming.media.processing.domain.ProcessingFailure;
import com.libra.streaming.media.processing.domain.RenditionPlan;
import com.libra.streaming.media.processing.domain.SourceMetadata;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * Probes the frozen source, then encodes exactly those proven bytes into one HLS rendition inside an
 * attempt-specific local workspace. The mutable staging object is never consulted: the input is the committed
 * frozen copy, re-measured before the encoder sees it. The stage stops with the output on local scratch. It stores
 * nothing, selects nothing, writes no event and never makes the asset ready or playable; later stages upload and
 * verify what the {@link Result.Transcoded} inventory describes.
 */
public final class SourceTranscoder {
    private final SourceProber prober;
    private final JobSources sources;
    private final SourceReader reader;
    private final MediaTranscoder transcoder;
    private final TranscodeWorkspaces workspaces;
    private final JobStages stages;
    private final long outputBudgetBytes;

    public SourceTranscoder(SourceProber prober, JobSources sources, SourceReader reader, MediaTranscoder transcoder,
            TranscodeWorkspaces workspaces, JobStages stages, long outputBudgetBytes) {
        if (outputBudgetBytes < 1) { throw new IllegalArgumentException("Output budget"); }
        this.prober = Objects.requireNonNull(prober);
        this.sources = Objects.requireNonNull(sources);
        this.reader = Objects.requireNonNull(reader);
        this.transcoder = Objects.requireNonNull(transcoder);
        this.workspaces = Objects.requireNonNull(workspaces);
        this.stages = Objects.requireNonNull(stages);
        this.outputBudgetBytes = outputBudgetBytes;
    }

    public Result transcode(JobLease lease, BooleanSupplier cancelled) throws InterruptedException {
        var probed = prober.probe(lease, cancelled);
        if (probed instanceof SourceProber.Result.LeaseLost) { return new Result.LeaseLost(); }
        if (probed instanceof SourceProber.Result.Rejected rejected) { return new Result.Rejected(rejected.outcome()); }
        var metadata = ((SourceProber.Result.Probed) probed).metadata();
        var target = sources.target(lease).orElse(null);
        // The probe selected a source under this lease; without one there is nothing legitimate to encode.
        if (target == null || target.selectedKey() == null) { return new Result.LeaseLost(); }
        if (!stages.beginTranscoding(lease)) { return new Result.LeaseLost(); }
        var plan = RenditionPlan.of(metadata);
        TranscodeWorkspaces.Workspace workspace = null;
        try (var copy = reader.open(target.selectedKey(), target.byteLength(), cancelled).orElse(null)) {
            if (copy == null) { return reject(ProcessingFailure.SOURCE_MISSING); }
            // The encoder reads the file that was just downloaded, so it is proven again rather than assumed.
            if (copy.bytes() != target.byteLength()) { return reject(ProcessingFailure.SIZE_MISMATCH); }
            if (!target.sha256().equals(copy.sha256())) { return reject(ProcessingFailure.CHECKSUM_MISMATCH); }
            if (cancelled.getAsBoolean()) { throw new InterruptedException(); }
            workspace = workspaces.open(lease, outputBudgetBytes);
            transcoder.transcode(copy.file(), plan, workspace.directory(), cancelled);
            var output = HlsPackage.seal(workspace.directory(), plan, metadata.durationMillis());
            if (cancelled.getAsBoolean()) { throw new InterruptedException(); }
            // An attempt that lost its lease while encoding must not hand its output to anyone.
            if (sources.target(lease).isEmpty()) { return new Result.LeaseLost(); }
            var done = new Result.Transcoded(output, metadata, workspace);
            workspace = null;
            return done;
        } catch (SourceStorage.Unavailable | MediaTranscoder.Unavailable | TranscodeWorkspaces.Unavailable unavailable) {
            return new Result.Rejected(MediaJobHandler.Outcome.retry(ProcessingFailure.PROCESSING_FAILED));
        } catch (MediaTranscoder.Undecodable undecodable) {
            return reject(ProcessingFailure.CORRUPT_INPUT);
        } catch (MediaTranscoder.OutputTooLarge tooLarge) {
            return reject(ProcessingFailure.UNSUPPORTED_MEDIA);
        } catch (HlsPackage.Malformed malformed) {
            // The tool exited cleanly but the workspace is not a whole rendition of the probed source. ffmpeg's HLS
            // muxer reports success when it cannot write (a full disk leaves an empty playlist and a cut segment), so
            // this is treated as a fault of the attempt, not a verdict on the input; the job's three-attempt budget
            // bounds a deterministic defect.
            return new Result.Rejected(MediaJobHandler.Outcome.retry(ProcessingFailure.PROCESSING_FAILED));
        } finally {
            if (workspace != null) { workspace.close(); }
        }
    }

    private static Result reject(ProcessingFailure failure) {
        return new Result.Rejected(MediaJobHandler.Outcome.reject(failure));
    }

    public sealed interface Result {
        /**
         * A verified local HLS output. The caller owns the workspace and must close this result to delete it, whether
         * or not it went on to store anything.
         */
        record Transcoded(HlsOutput output, SourceMetadata source, TranscodeWorkspaces.Workspace workspace)
                implements Result, AutoCloseable {
            @Override
            public void close() { workspace.close(); }
        }
        /** Input rejection (permanent) or a retryable fault, in the worker's existing outcome vocabulary. */
        record Rejected(MediaJobHandler.Outcome outcome) implements Result {}
        /** The lease was no longer current; the caller must not record any outcome. */
        record LeaseLost() implements Result {}
    }
}
