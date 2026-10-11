package com.libra.streaming.media.processing.infrastructure;

import com.libra.streaming.media.processing.application.BoundedMediaWorker;
import com.libra.streaming.media.processing.application.MediaJobHandler;
import com.libra.streaming.media.processing.application.OutputFinalizer;
import com.libra.streaming.media.processing.application.OutputSelections;
import com.libra.streaming.media.processing.application.OutputStorage;
import com.libra.streaming.media.processing.application.SourceProber;
import com.libra.streaming.media.processing.domain.JobLease;
import com.libra.streaming.media.processing.domain.ProcessingFailure;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import static org.assertj.core.api.Assertions.*;

/**
 * Finalization with nothing mocked unless a test says so: real SeaweedFS holds staging, the frozen copy and the HLS
 * output, real PostgreSQL holds the lease, stages, READY selection and outbox, and the real ffprobe and ffmpeg judge
 * and encode generated fixtures. Kafka is not involved: the READY event is a committed PENDING outbox row.
 */
class OutputFinalizationIntegrationTest extends FinalizationFixture {
    @Test
    void verifiedOutputBecomesOneAuthoritativeReadySelectionWithItsDurableEvent() throws Exception {
        var lease = stagedClaim("valid-1080p-aac.mp4");

        var result = finalizer.complete(lease, () -> false);

        assertThat(result).isInstanceOf(OutputFinalizer.Result.Ready.class);
        String prefix = prefix(upload, 1);
        var asset = asset();
        assertThat(asset.get("state")).isEqualTo("READY");
        assertThat(asset.get("selected_source_key")).isEqualTo(attemptKey(upload, 1));
        assertThat(asset.get("output_prefix")).isEqualTo(prefix);
        assertThat(asset.get("master_manifest_key")).isEqualTo(prefix + "/master.m3u8");
        assertThat(asset.get("aggregate_version")).isEqualTo(2L);
        int seconds = (Integer) asset.get("duration_seconds");
        long sourceMillis = jdbc.queryForObject("SELECT duration_millis FROM media_source_metadata WHERE asset_id = ?",
                Long.class, upload.assetId());
        assertThat(Math.abs(seconds * 1000L - sourceMillis)).isLessThanOrEqualTo(1000);
        assertThat(((OutputFinalizer.Result.Ready) result).selection().durationSeconds()).isEqualTo(seconds);

        var job = job();
        assertThat(job.get("stage")).isEqualTo("SUCCEEDED");
        assertThat(job.get("lease_token")).isNull();
        assertThat(job.get("lease_until")).isNull();
        assertThat(job.get("failure_code")).isNull();
        assertThat(job.get("attempt_count")).isEqualTo(1);
        assertThat(uploads.read(upload.uploadId()).assetState()).isEqualTo("READY");

        // Exactly the first claim's PROCESSING and the READY event, in aggregate order, PENDING for the publisher.
        assertThat(events()).containsExactly("PROCESSING", "READY");
        var ready = jdbc.queryForMap("""
                SELECT event_id, aggregate_version, topic, event_type, schema_version, correlation_id, payload::text
                    AS payload, delivery_state, lease_token, attempt_count, sent_at
                FROM media_outbox_events WHERE asset_id = ? AND payload->>'state' = 'READY'
                """, upload.assetId());
        assertThat(ready.get("aggregate_version")).isEqualTo(2L);
        assertThat(ready.get("topic")).isEqualTo("media.assets.v1");
        assertThat(ready.get("event_type")).isEqualTo("MediaAssetStateChanged");
        assertThat(ready.get("schema_version")).isEqualTo(1);
        assertThat(ready.get("correlation_id")).isEqualTo(upload.requestId());
        assertThat(ready.get("delivery_state")).isEqualTo("PENDING");
        assertThat(ready.get("lease_token")).isNull();
        assertThat(ready.get("sent_at")).isNull();
        var payload = mapper.readTree((String) ready.get("payload"));
        assertThat(payload.propertyNames()).containsExactlyInAnyOrder("contentId", "bindingId", "assetId",
                "assetVersion", "state", "durationSeconds");
        assertThat(payload.get("contentId").asString()).isEqualTo(upload.contentId().toString());
        assertThat(payload.get("bindingId").asString()).isEqualTo(upload.bindingId().toString());
        assertThat(payload.get("assetId").asString()).isEqualTo(upload.assetId().toString());
        assertThat(payload.get("assetVersion").asLong()).isEqualTo(1);
        assertThat(payload.get("state").asString()).isEqualTo("READY");
        assertThat(payload.get("durationSeconds").asInt()).isEqualTo(seconds);

        // The stored generation is the closed inventory: master, variant and exactly the segments it names.
        var keys = hlsKeys("hls/" + upload.uploadId() + "/");
        var master = hlsText(prefix + "/master.m3u8");
        assertThat(master.lines().filter(line -> !line.startsWith("#")).toList()).containsExactly("rendition.m3u8");
        var named = hlsText(prefix + "/rendition.m3u8").lines().filter(line -> !line.startsWith("#"))
                .map(name -> prefix + "/" + name).toList();
        assertThat(named).isNotEmpty().allMatch(key -> key.matches(".*/segment-\\d{5}\\.ts"));
        assertThat(keys).containsExactlyInAnyOrderElementsOf(
                java.util.stream.Stream.concat(Set.of(prefix + "/master.m3u8", prefix + "/rendition.m3u8").stream(),
                        named.stream()).toList());
        assertThat(client.headObject(request -> request.bucket(HLS).key(prefix + "/master.m3u8")).contentType())
                .isEqualTo("application/vnd.apple.mpegurl");
        assertThat(client.headObject(request -> request.bucket(HLS).key(named.getFirst())).contentType())
                .isEqualTo("video/mp2t");
        assertThat(workspaceDirectories()).isEmpty();

        // The claim is spent: nothing can renew, re-select or re-run it, and nothing is left to claim.
        assertThat(leases.renew(lease, Duration.ofSeconds(30))).isFalse();
        assertThat(selections.select(lease, ((OutputFinalizer.Result.Ready) result).selection())).isFalse();
        assertThat(finalizer.complete(lease, () -> false)).isInstanceOf(OutputFinalizer.Result.LeaseLost.class);
        assertThat(leases.claim(Duration.ofSeconds(30))).isEmpty();
        assertThat(events()).hasSize(2);
        assertThat(hlsKeys("hls/" + upload.uploadId() + "/")).isEqualTo(keys);
    }

    @Test
    void aMissingStoredObjectPreventsReadyAndTheRetryFinalizesAFreshGeneration() throws Exception {
        var lease = stagedClaim("valid-160x96-silent.mp4");
        // Storage acknowledges the first segment and then loses it, the way an unreliable write would.
        OutputStorage lossy = new OutputStorage() {
            @Override public String prefix(JobLease claim) { return outputs.prefix(claim); }
            @Override public void store(String key, Path file, String contentType) {
                outputs.store(key, file, contentType);
                if (key.endsWith("/segment-00000.ts")) {
                    client.deleteObject(DeleteObjectRequest.builder().bucket(HLS).key(key).build());
                }
            }
            @Override public Optional<Readback> read(String key, long bytes, BooleanSupplier cancelled)
                    throws InterruptedException {
                return outputs.read(key, bytes, cancelled);
            }
        };

        var result = new OutputFinalizer(transcoder, lossy, selections).complete(lease, () -> false);

        assertThat(result).isEqualTo(new OutputFinalizer.Result.Rejected(
                MediaJobHandler.Outcome.retry(ProcessingFailure.PROCESSING_FAILED)));
        assertNotReady();
        assertThat(job().get("stage")).isEqualTo("FINALIZING");
        assertThat(workspaceDirectories()).isEmpty();
        var orphans = hlsKeys(prefix(upload, 1) + "/");
        assertThat(orphans).isNotEmpty().doesNotContain(prefix(upload, 1) + "/segment-00000.ts");

        // The worker records the transient failure; the next attempt encodes again into its own prefix.
        assertThat(leases.retry(lease, ProcessingFailure.PROCESSING_FAILED, Duration.ofMillis(1))).isTrue();
        var second = nextClaim();
        assertThat(second.attempt()).isEqualTo(2);
        assertThat(events()).containsExactly("PROCESSING"); // a retry claim is not a new transition

        assertThat(finalizer.complete(second, () -> false)).isInstanceOf(OutputFinalizer.Result.Ready.class);

        assertThat(asset().get("output_prefix")).isEqualTo(prefix(upload, 2));
        assertThat(asset().get("selected_source_key")).isEqualTo(attemptKey(upload, 1));
        assertThat(events()).containsExactly("PROCESSING", "READY");
        // The failed attempt's objects stay unreferenced for scoped cleanup; they are not the selection.
        assertThat(hlsKeys(prefix(upload, 1) + "/")).isEqualTo(orphans);
        assertThat(hlsKeys(prefix(upload, 2) + "/")).contains(prefix(upload, 2) + "/segment-00000.ts");
    }

    @Test
    void aFailedOutboxWriteRollsBackReadyAndItsGeneration() throws Exception {
        var lease = stagedClaim("valid-160x96-silent.mp4");
        jdbc.execute("""
                CREATE FUNCTION m23_refuse_ready() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.payload->>'state' = 'READY' THEN RAISE EXCEPTION 'm23 outbox refused'; END IF;
                    RETURN NEW;
                END $$""");
        jdbc.execute("""
                CREATE TRIGGER m23_refuse_ready BEFORE INSERT ON media_outbox_events
                FOR EACH ROW EXECUTE FUNCTION m23_refuse_ready()""");
        try {
            // The refused commit propagates; the worker maps an exception from its handler to a retry.
            assertThatThrownBy(() -> finalizer.complete(lease, () -> false)).isInstanceOf(RuntimeException.class)
                    .hasStackTraceContaining("m23 outbox refused");

            // Neither the state, the generation, the duration, the version nor the job's success survived.
            assertNotReady();
            var job = job();
            assertThat(job.get("stage")).isEqualTo("FINALIZING");
            assertThat(job.get("lease_token")).isEqualTo(lease.token());
            assertThat(workspaceDirectories()).isEmpty();
            assertThat(hlsKeys(prefix(upload, 1) + "/")).contains(prefix(upload, 1) + "/master.m3u8");
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS m23_refuse_ready ON media_outbox_events");
            jdbc.execute("DROP FUNCTION IF EXISTS m23_refuse_ready()");
        }
        assertNotReady();
        assertThat(leases.retry(lease, ProcessingFailure.PROCESSING_FAILED, Duration.ofMillis(1))).isTrue();
        var second = nextClaim();

        assertThat(finalizer.complete(second, () -> false)).isInstanceOf(OutputFinalizer.Result.Ready.class);

        assertThat(asset().get("output_prefix")).isEqualTo(prefix(upload, 2));
        assertThat(asset().get("aggregate_version")).isEqualTo(2L);
        assertThat(events()).containsExactly("PROCESSING", "READY");
    }

    @Test
    void onlyTheCurrentLeaseSelectsOutputAndAStaleAttemptCannotReplaceIt() throws Exception {
        var stale = stagedClaim("valid-160x96-silent.mp4");
        var successor = new java.util.concurrent.atomic.AtomicReference<JobLease>();
        // The old claim runs out and another instance claims the job just before the old commit.
        OutputSelections overtaken = new OutputSelections() {
            @Override public boolean beginFinalizing(JobLease claim) { return selections.beginFinalizing(claim); }
            @Override public boolean select(JobLease claim, Selection selection) {
                expire(claim);
                successor.set(claim());
                return selections.select(claim, selection);
            }
        };

        var result = new OutputFinalizer(transcoder, outputs, overtaken).complete(stale, () -> false);

        assertThat(result).isInstanceOf(OutputFinalizer.Result.LeaseLost.class);
        assertNotReady();
        assertThat(job().get("stage")).isEqualTo("CLAIMED");
        assertThat(finalizer.complete(successor.get(), () -> false)).isInstanceOf(OutputFinalizer.Result.Ready.class);
        var selected = asset();
        assertThat(selected.get("output_prefix")).isEqualTo(prefix(upload, 2));

        // The stale attempt can neither finalize nor point READY at its own complete generation.
        var staleSelection = new OutputSelections.Selection(attemptKey(upload, 1), prefix(upload, 1),
                prefix(upload, 1) + "/master.m3u8", 2);
        assertThat(hlsKeys(prefix(upload, 1) + "/")).contains(staleSelection.masterKey());
        assertThat(selections.beginFinalizing(stale)).isFalse();
        assertThat(selections.select(stale, staleSelection)).isFalse();
        assertThat(asset()).isEqualTo(selected);
        assertThat(events()).containsExactly("PROCESSING", "READY");
    }

    @Test
    void theCommitIsFencedToAFinalizingJobAndTheSourceThatWasEncoded() throws Exception {
        var lease = stagedClaim("valid-160x96-silent.mp4");
        assertThat(prober.probe(lease, () -> false)).isInstanceOf(SourceProber.Result.Probed.class);
        // A job that never transcoded cannot finalize.
        assertThat(selections.beginFinalizing(lease)).isFalse();
        assertThat(stages.beginTranscoding(lease)).isTrue();
        var impostor = new JobLease(lease.jobId(), lease.uploadId(), lease.assetId(), lease.assetVersion(),
                lease.attempt(), UUID.randomUUID(), lease.expiresAt());
        assertThat(selections.beginFinalizing(impostor)).isFalse();
        assertThat(selections.beginFinalizing(lease)).isTrue();
        assertThat(selections.beginFinalizing(lease)).isTrue();
        assertThat(job().get("stage")).isEqualTo("FINALIZING");

        String prefix = prefix(upload, 1);
        var foreign = new OutputSelections.Selection("sources/" + UUID.randomUUID() + "/attempt-1/source.mp4",
                prefix, prefix + "/master.m3u8", 3);
        // The repository's exception translation wraps the invariant violation; either way nothing commits.
        assertThatThrownBy(() -> selections.select(lease, foreign)).hasMessage("Selected output is immutable");
        assertNotReady();
        assertThat(job().get("lease_token")).isEqualTo(lease.token());
        var selection = new OutputSelections.Selection(attemptKey(upload, 1), prefix, prefix + "/master.m3u8", 3);
        assertThat(selections.select(impostor, selection)).isFalse();
        assertNotReady();

        // This adapter-level check commits without objects; the finalizer is what proves them first.
        assertThat(selections.select(lease, selection)).isTrue();
        assertThat(asset().get("state")).isEqualTo("READY");
        assertThat(job().get("stage")).isEqualTo("SUCCEEDED");
        assertThat(selections.select(lease, selection)).isFalse();
        assertThat(events()).containsExactly("PROCESSING", "READY");
    }

    @Test
    void aRejectedSourceEndsWithOneFailedEventAndNoDuration() throws Exception {
        var lease = stagedClaim("invalid-hevc.mp4");

        var outcome = handler.process(lease, () -> false);

        assertThat(outcome).isEqualTo(MediaJobHandler.Outcome.reject(ProcessingFailure.UNSUPPORTED_MEDIA));
        assertThat(leases.fail(lease, outcome.failure())).isTrue();
        assertThat(asset().get("state")).isEqualTo("FAILED");
        assertThat(asset().get("aggregate_version")).isEqualTo(2L);
        assertThat(events()).containsExactly("PROCESSING", "FAILED");
        var failed = mapper.readTree(jdbc.queryForObject("""
                SELECT payload::text FROM media_outbox_events WHERE asset_id = ? AND aggregate_version = 2
                """, String.class, upload.assetId()));
        assertThat(failed.propertyNames()).containsExactlyInAnyOrder("contentId", "bindingId", "assetId",
                "assetVersion", "state");
        assertThat(hlsKeys("hls/" + upload.uploadId() + "/")).isEmpty();
    }

    @Test
    void theWorkerRunsTheProductionHandlerToReadyAndRecordsNothingAfterIt() throws Exception {
        assertThat(handler).isSameAs(finalizer);
        byte[] content = MediaFixtures.bytes("valid-160x96-silent.mp4");
        upload = queued(content);
        stage(upload, content);
        var reports = new CopyOnWriteArrayList<String>();
        var settings = new BoundedMediaWorker.Settings(Duration.ofMillis(50), Duration.ofSeconds(10),
                Duration.ofSeconds(1), Duration.ofSeconds(30), Duration.ofSeconds(10));
        try (var worker = new BoundedMediaWorker(leases, handler, settings, reports::add)) {
            worker.start();
            long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
            while (!"SUCCEEDED".equals(job().get("stage"))) {
                assertThat(System.nanoTime()).as("worker reached READY: %s %s", job(), reports).isLessThan(deadline);
                Thread.sleep(50);
            }
            Thread.sleep(300); // a few more polls: nothing else may be written for the finished job
        }
        assertThat(reports).isEmpty();
        assertThat(asset().get("state")).isEqualTo("READY");
        assertThat(job().get("attempt_count")).isEqualTo(1);
        assertThat(job().get("failure_code")).isNull();
        assertThat(events()).containsExactly("PROCESSING", "READY");
        assertThat(workspaceDirectories()).isEmpty();
    }
}
