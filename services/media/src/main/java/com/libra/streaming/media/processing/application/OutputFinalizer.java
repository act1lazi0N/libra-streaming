package com.libra.streaming.media.processing.application;

import com.libra.streaming.media.processing.domain.JobLease;
import com.libra.streaming.media.processing.domain.ProcessingFailure;
import com.libra.streaming.media.processing.domain.SourceMetadata;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * The last stage of a job and the production {@link MediaJobHandler}: encodes the frozen source, stores the checked
 * inventory under this claim's private prefix, reads every object back, and only then commits READY. The commit is
 * one fenced transaction holding the output generation, the validated duration, the READY state, the next aggregate
 * version and its outbox event, so a missing object, a lost lease or a failed event write leaves no READY behind.
 * Objects stored by an attempt that does not commit stay unreferenced under that attempt's prefix for scoped cleanup;
 * they can never replace a selected generation.
 */
public final class OutputFinalizer implements MediaJobHandler {
    static final String PLAYLIST_TYPE = "application/vnd.apple.mpegurl";
    static final String SEGMENT_TYPE = "video/mp2t";
    private static final int MAX_DURATION_SECONDS = (int) (SourceMetadata.MAX_DURATION_MILLIS / 1000);

    private final SourceTranscoder transcoder;
    private final OutputStorage storage;
    private final OutputSelections selections;

    public OutputFinalizer(SourceTranscoder transcoder, OutputStorage storage, OutputSelections selections) {
        this.transcoder = Objects.requireNonNull(transcoder);
        this.storage = Objects.requireNonNull(storage);
        this.selections = Objects.requireNonNull(selections);
    }

    @Override
    public Outcome process(JobLease lease, BooleanSupplier cancelled) throws InterruptedException {
        return switch (complete(lease, cancelled)) {
            case Result.Ready ready -> Outcome.settled();
            case Result.LeaseLost lost -> Outcome.settled();
            case Result.Rejected rejected -> rejected.outcome();
        };
    }

    public Result complete(JobLease lease, BooleanSupplier cancelled) throws InterruptedException {
        var encoded = transcoder.transcode(lease, cancelled);
        if (encoded instanceof SourceTranscoder.Result.LeaseLost) { return new Result.LeaseLost(); }
        if (encoded instanceof SourceTranscoder.Result.Rejected rejected) {
            return new Result.Rejected(rejected.outcome());
        }
        try (var done = (SourceTranscoder.Result.Transcoded) encoded) {
            var output = done.output();
            // What leaves the workspace must still be exactly what was sealed; the playlists name nothing else.
            HlsPackage.requireConsistent(output);
            var inventory = inventory(output);
            if (!selections.beginFinalizing(lease)) { return new Result.LeaseLost(); }
            String prefix = storage.prefix(lease);
            // Segments first and the master last, so no stored playlist ever precedes an object it names.
            for (var item : inventory) {
                if (cancelled.getAsBoolean()) { throw new InterruptedException(); }
                storage.store(prefix + "/" + item.name(), item.file(), item.contentType());
            }
            // A write that returned is not proof of a durable, readable object: every one is read back and compared.
            for (var item : inventory) {
                var stored = storage.read(prefix + "/" + item.name(), item.bytes(), cancelled).orElse(null);
                if (stored == null || stored.bytes() != item.bytes() || !stored.sha256().equals(item.sha256())) {
                    return retry();
                }
            }
            if (cancelled.getAsBoolean()) { throw new InterruptedException(); }
            var selection = new OutputSelections.Selection(done.sourceKey(), prefix,
                    prefix + "/" + output.masterName(), durationSeconds(output.durationMillis()));
            return selections.select(lease, selection) ? new Result.Ready(selection) : new Result.LeaseLost();
        } catch (OutputStorage.Unavailable | HlsPackage.Malformed fault) {
            // A storage fault or a workspace that changed after sealing says nothing about the input.
            return retry();
        }
    }

    /**
     * The duration Core receives, from the rendition viewers will play. The policy proved the source is at most
     * 600 s; an encoder can overhang that by part of a frame, which must not turn a valid clip into an invalid event.
     */
    static int durationSeconds(long millis) {
        return Math.clamp(Math.round(millis / 1000.0), 1, MAX_DURATION_SECONDS);
    }

    private static List<Item> inventory(HlsOutput output) {
        var items = new ArrayList<Item>();
        for (var segment : output.segments()) { items.add(item(output.directory(), segment.name(), SEGMENT_TYPE)); }
        items.add(item(output.directory(), output.variantName(), PLAYLIST_TYPE));
        items.add(item(output.directory(), output.masterName(), PLAYLIST_TYPE));
        return items;
    }

    private static Item item(Path directory, String name, String contentType) {
        var file = directory.resolve(name);
        try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            var digest = sha256();
            byte[] buffer = new byte[65536];
            long bytes = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
                bytes += read;
            }
            return new Item(name, file, contentType, bytes, HexFormat.of().formatHex(digest.digest()));
        } catch (IOException exception) {
            throw new HlsPackage.Malformed("IO");
        }
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException missing) { throw new IllegalStateException("SHA-256 unavailable", missing); }
    }

    private static Result retry() {
        return new Result.Rejected(Outcome.retry(ProcessingFailure.PROCESSING_FAILED));
    }

    private record Item(String name, Path file, String contentType, long bytes, String sha256) {}

    public sealed interface Result {
        /** READY, its output generation and its outbox event are committed for this asset. */
        record Ready(OutputSelections.Selection selection) implements Result {}
        /** Input rejection (permanent) or a retryable fault, in the worker's existing outcome vocabulary. */
        record Rejected(Outcome outcome) implements Result {}
        /** The lease was no longer current; the caller must not record any outcome. */
        record LeaseLost() implements Result {}
    }
}
