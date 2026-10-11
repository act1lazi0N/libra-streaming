package com.libra.streaming.media.processing.application;

import com.libra.streaming.media.processing.domain.JobLease;
import com.libra.streaming.media.processing.domain.ProcessingFailure;
import com.libra.streaming.media.processing.domain.RenditionPlan;
import com.libra.streaming.media.processing.domain.SourceMetadata;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Finalization orchestration with in-memory ports; real storage, PostgreSQL and ffmpeg are a separate suite. */
class OutputFinalizerTest {
    private static final RenditionPlan PLAN = new RenditionPlan(1280, 720, 30, 1, 2);
    private static final String SOURCE = "sources/u/attempt-1/source.mp4";
    private static final String PREFIX = "hls/u/attempt-1";
    @TempDir Path scratch;
    private final JobLease lease = new JobLease(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, 1,
            UUID.randomUUID(), Instant.now().plusSeconds(30));
    private SourceTranscoder transcoder;
    private Storage storage;
    private Selections selections;
    private OutputFinalizer finalizer;
    private boolean workspaceClosed;

    @BeforeEach
    void setUp() throws Exception {
        transcoder = mock(SourceTranscoder.class);
        storage = new Storage();
        selections = new Selections();
        finalizer = new OutputFinalizer(transcoder, storage, selections);
        when(transcoder.transcode(any(), any())).thenAnswer(call -> transcoded(10_500));
    }

    /** A sealed three-segment workspace, as the transcode stage hands it over. */
    private SourceTranscoder.Result.Transcoded transcoded(long millis) throws IOException {
        var directory = Files.createTempDirectory(scratch, "hls-attempt-1-");
        Files.writeString(directory.resolve(HlsPackage.VARIANT), """
                #EXTM3U
                #EXT-X-VERSION:6
                #EXT-X-TARGETDURATION:4
                #EXT-X-MEDIA-SEQUENCE:0
                #EXT-X-PLAYLIST-TYPE:VOD
                #EXT-X-INDEPENDENT-SEGMENTS
                #EXTINF:4.000000,
                segment-00000.ts
                #EXTINF:4.000000,
                segment-00001.ts
                #EXTINF:%s,
                segment-00002.ts
                #EXT-X-ENDLIST
                """.formatted(String.format(java.util.Locale.ROOT, "%.6f", (millis - 8000) / 1000.0)));
        for (int index = 0; index < 3; index++) {
            var content = new byte[188 * (10 + index)];
            for (int at = 0; at < content.length; at += 188) { content[at] = 0x47; }
            Files.write(directory.resolve(String.format("segment-%05d.ts", index)), content);
        }
        var output = HlsPackage.seal(directory, PLAN, millis);
        var source = new SourceMetadata(millis, 1920, 1080, 0, 1920, 1080, 30, 1, "High", null);
        return new SourceTranscoder.Result.Transcoded(output, source, SOURCE, new TranscodeWorkspaces.Workspace() {
            @Override public Path directory() { return directory; }
            @Override public void close() {
                workspaceClosed = true;
                try (var files = Files.list(directory)) {
                    for (var file : files.toList()) { Files.delete(file); }
                    Files.delete(directory);
                } catch (IOException exception) { throw new AssertionError(exception); }
            }
        });
    }

    @Test
    void storesTheWholeInventoryReadsItBackAndThenCommitsReady() throws Exception {
        var result = finalizer.complete(lease, () -> false);

        var ready = (OutputFinalizer.Result.Ready) result;
        assertThat(ready.selection()).isEqualTo(new OutputSelections.Selection(SOURCE, PREFIX,
                PREFIX + "/master.m3u8", 11));
        // Segments first, the variant next and the master last; every object was read back before the commit.
        assertThat(storage.stored.keySet()).containsExactly(PREFIX + "/segment-00000.ts", PREFIX + "/segment-00001.ts",
                PREFIX + "/segment-00002.ts", PREFIX + "/rendition.m3u8", PREFIX + "/master.m3u8");
        assertThat(storage.types).containsExactly("video/mp2t", "video/mp2t", "video/mp2t",
                "application/vnd.apple.mpegurl", "application/vnd.apple.mpegurl");
        assertThat(storage.reads).containsExactlyElementsOf(storage.stored.keySet());
        assertThat(new String(storage.stored.get(PREFIX + "/master.m3u8"))).endsWith("\nrendition.m3u8\n");
        assertThat(selections.calls).containsExactly("FINALIZING", "SELECT");
        assertThat(workspaceClosed).isTrue();
    }

    @Test
    void theJobHandlerSettlesAReadyJobAndALostOneButHandsBackRealFailures() throws Exception {
        assertThat(finalizer.process(lease, () -> false)).isEqualTo(MediaJobHandler.Outcome.settled());

        selections = new Selections();
        selections.select = false;
        finalizer = new OutputFinalizer(transcoder, storage, selections);
        assertThat(finalizer.process(lease, () -> false)).isEqualTo(MediaJobHandler.Outcome.settled());

        var rejection = MediaJobHandler.Outcome.reject(ProcessingFailure.CORRUPT_INPUT);
        when(transcoder.transcode(any(), any())).thenReturn(new SourceTranscoder.Result.Rejected(rejection));
        assertThat(finalizer.process(lease, () -> false)).isEqualTo(rejection);
        when(transcoder.transcode(any(), any())).thenReturn(new SourceTranscoder.Result.LeaseLost());
        assertThat(finalizer.complete(lease, () -> false)).isInstanceOf(OutputFinalizer.Result.LeaseLost.class);
    }

    @Test
    void aMissingOrDifferentReadbackIsARetryableFaultAndNeverReady() throws Exception {
        for (var damage : List.of("missing", "short", "changed")) {
            storage = new Storage();
            storage.damage = damage;
            selections = new Selections();
            finalizer = new OutputFinalizer(transcoder, storage, selections);
            workspaceClosed = false;

            var result = finalizer.complete(lease, () -> false);

            assertThat(result).as(damage).isEqualTo(new OutputFinalizer.Result.Rejected(
                    MediaJobHandler.Outcome.retry(ProcessingFailure.PROCESSING_FAILED)));
            assertThat(selections.calls).as(damage).containsExactly("FINALIZING");
            assertThat(workspaceClosed).as(damage).isTrue();
        }
    }

    @Test
    void aStorageFaultIsRetryableAndStopsBeforeTheCommit() throws Exception {
        storage.unavailableOn = PREFIX + "/rendition.m3u8";

        var result = finalizer.complete(lease, () -> false);

        assertThat(result).isEqualTo(new OutputFinalizer.Result.Rejected(
                MediaJobHandler.Outcome.retry(ProcessingFailure.PROCESSING_FAILED)));
        // The master, which names everything else, was never written.
        assertThat(storage.stored).doesNotContainKey(PREFIX + "/master.m3u8");
        assertThat(selections.calls).containsExactly("FINALIZING");
        assertThat(workspaceClosed).isTrue();
    }

    @Test
    void aWorkspaceChangedAfterSealingNeverLeavesIt() throws Exception {
        when(transcoder.transcode(any(), any())).thenAnswer(call -> {
            var done = transcoded(10_500);
            Files.writeString(done.output().directory().resolve("notes.txt"), "not ours");
            return done;
        });

        var result = finalizer.complete(lease, () -> false);

        assertThat(result).isEqualTo(new OutputFinalizer.Result.Rejected(
                MediaJobHandler.Outcome.retry(ProcessingFailure.PROCESSING_FAILED)));
        assertThat(storage.stored).isEmpty();
        assertThat(selections.calls).isEmpty();
        assertThat(workspaceClosed).isTrue();
    }

    @Test
    void aSegmentRewrittenAfterSealingIsNeverSelectedEvenAtTheSameSize() throws Exception {
        when(transcoder.transcode(any(), any())).thenAnswer(call -> {
            var done = transcoded(10_500);
            // Same name, same size, whole packets: only the bytes the seal vouched for differ.
            var segment = done.output().directory().resolve("segment-00001.ts");
            var bytes = Files.readAllBytes(segment);
            bytes[1] = 0x1f;
            Files.write(segment, bytes);
            return done;
        });

        var result = finalizer.complete(lease, () -> false);

        assertThat(result).isEqualTo(new OutputFinalizer.Result.Rejected(
                MediaJobHandler.Outcome.retry(ProcessingFailure.PROCESSING_FAILED)));
        assertThat(selections.calls).containsExactly("FINALIZING");
        assertThat(workspaceClosed).isTrue();
    }

    @Test
    void aPlaylistChangedWhileSegmentsAreStoredIsNeverSelected() throws Exception {
        var damaged = new Storage() {
            @Override public void store(String key, Path file, String contentType) {
                if (key.endsWith("/segment-00000.ts")) {
                    // Rewrite the master between the consistency check and its own upload.
                    try {
                        var master = file.resolveSibling(HlsPackage.MASTER);
                        Files.writeString(master, Files.readString(master)
                                + "#EXT-X-SESSION-DATA:DATA-ID=\"x\",URI=\"https://example.invalid/x\"\n");
                    } catch (IOException exception) { throw new AssertionError(exception); }
                }
                super.store(key, file, contentType);
            }
        };
        finalizer = new OutputFinalizer(transcoder, damaged, selections);

        var result = finalizer.complete(lease, () -> false);

        assertThat(result).isEqualTo(new OutputFinalizer.Result.Rejected(
                MediaJobHandler.Outcome.retry(ProcessingFailure.PROCESSING_FAILED)));
        assertThat(new String(damaged.stored.get(PREFIX + "/master.m3u8"))).contains("EXT-X-SESSION-DATA");
        assertThat(selections.calls).containsExactly("FINALIZING");
    }

    @Test
    void aLeaseLostBeforeFinalizingStoresNothing() throws Exception {
        selections.finalizing = false;

        assertThat(finalizer.complete(lease, () -> false)).isInstanceOf(OutputFinalizer.Result.LeaseLost.class);

        assertThat(storage.stored).isEmpty();
        assertThat(selections.calls).containsExactly("FINALIZING");
        assertThat(workspaceClosed).isTrue();
    }

    @Test
    void aLeaseLostAtTheCommitIsNotReady() throws Exception {
        selections.select = false;

        assertThat(finalizer.complete(lease, () -> false)).isInstanceOf(OutputFinalizer.Result.LeaseLost.class);
        assertThat(selections.calls).containsExactly("FINALIZING", "SELECT");
    }

    @Test
    void cancellationStopsUploadingAndReleasesTheWorkspace() throws Exception {
        var calls = new int[1];
        BooleanSupplier cancelled = () -> ++calls[0] > 2;

        assertThatThrownBy(() -> finalizer.complete(lease, cancelled)).isInstanceOf(InterruptedException.class);

        assertThat(storage.stored).hasSize(2);
        assertThat(selections.calls).containsExactly("FINALIZING");
        assertThat(workspaceClosed).isTrue();
    }

    @Test
    void theReadyDurationIsTheRenditionRoundedAndKeptWithinTheSourcePolicy() {
        assertThat(OutputFinalizer.durationSeconds(1)).isEqualTo(1);
        assertThat(OutputFinalizer.durationSeconds(499)).isEqualTo(1);
        assertThat(OutputFinalizer.durationSeconds(1_499)).isEqualTo(1);
        assertThat(OutputFinalizer.durationSeconds(1_500)).isEqualTo(2);
        assertThat(OutputFinalizer.durationSeconds(10_500)).isEqualTo(11);
        assertThat(OutputFinalizer.durationSeconds(600_000)).isEqualTo(600);
        assertThat(OutputFinalizer.durationSeconds(600_700)).isEqualTo(600);
    }

    @Test
    void aSelectionCannotPointOutsideItsPrefix() {
        assertThatThrownBy(() -> new OutputSelections.Selection(SOURCE, PREFIX, "hls/u/attempt-2/master.m3u8", 5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OutputSelections.Selection(SOURCE, PREFIX, PREFIX + "/x/master.m3u8", 5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OutputSelections.Selection(SOURCE, PREFIX + "/", PREFIX + "//master.m3u8", 5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OutputSelections.Selection(SOURCE, PREFIX, PREFIX + "/master.m3u8", 601))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OutputSelections.Selection(" ", PREFIX, PREFIX + "/master.m3u8", 5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static String sha(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception exception) { throw new AssertionError(exception); }
    }

    private class Storage implements OutputStorage {
        final Map<String, byte[]> stored = new LinkedHashMap<>();
        final List<String> types = new ArrayList<>();
        final List<String> reads = new ArrayList<>();
        String damage;
        String unavailableOn;

        @Override public String prefix(JobLease claim) { return "hls/u/attempt-" + claim.attempt(); }

        @Override public void store(String key, Path file, String contentType) {
            if (key.equals(unavailableOn)) { throw new Unavailable(); }
            try { stored.put(key, Files.readAllBytes(file)); }
            catch (IOException exception) { throw new AssertionError(exception); }
            types.add(contentType);
        }

        @Override public Optional<Readback> read(String key, long expectedBytes, BooleanSupplier cancelled) {
            reads.add(key);
            var bytes = stored.get(key);
            if (bytes == null || (key.endsWith("segment-00001.ts") && "missing".equals(damage))) {
                return Optional.empty();
            }
            if (key.endsWith("segment-00001.ts") && "short".equals(damage)) {
                return Optional.of(new Readback(bytes.length - 1, sha(bytes)));
            }
            if (key.endsWith("rendition.m3u8") && "changed".equals(damage)) {
                return Optional.of(new Readback(bytes.length, sha("other".getBytes())));
            }
            return Optional.of(new Readback(bytes.length, sha(bytes)));
        }
    }

    private static final class Selections implements OutputSelections {
        final List<String> calls = new ArrayList<>();
        boolean finalizing = true;
        boolean select = true;

        @Override public boolean beginFinalizing(JobLease claim) {
            calls.add("FINALIZING");
            return finalizing;
        }

        @Override public boolean select(JobLease claim, Selection selection) {
            calls.add("SELECT");
            return select;
        }
    }
}
