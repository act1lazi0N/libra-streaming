package com.libra.streaming.media.processing.infrastructure;

import com.libra.streaming.media.processing.application.JobStages;
import com.libra.streaming.media.processing.application.MediaJobHandler;
import com.libra.streaming.media.processing.application.OutputFinalizer;
import com.libra.streaming.media.processing.application.OutputSelections;
import com.libra.streaming.media.processing.application.OutputStorage;
import com.libra.streaming.media.processing.application.SourceProber;
import com.libra.streaming.media.processing.application.SourceTranscoder;
import com.libra.streaming.media.processing.domain.JobLease;
import com.libra.streaming.media.upload.infrastructure.MediaPersistenceService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

/**
 * Real SeaweedFS, PostgreSQL, ffprobe and ffmpeg for the finalization suites, with one shared Spring context. Kafka is
 * not involved: an event is a committed PENDING outbox row.
 */
abstract class FinalizationFixture extends SourceStorageFixture {
    static final String HLS = "libra-hls";

    @DynamicPropertySource
    static void tools(DynamicPropertyRegistry registry) {
        registry.add("libra.media.probe.executable", () -> FfprobeLocator.locate().toString());
        registry.add("libra.media.probe.timeout", () -> "30s");
        registry.add("libra.media.transcode.executable", () -> MediaFixtures.ffmpeg().toString());
        registry.add("libra.media.transcode.timeout", () -> "5m");
        registry.add("libra.media.storage.max-object-bytes", () -> "8388608");
    }

    @Autowired OutputFinalizer finalizer;
    @Autowired MediaJobHandler handler;
    @Autowired SourceTranscoder transcoder;
    @Autowired SourceProber prober;
    @Autowired OutputStorage outputs;
    @Autowired OutputSelections selections;
    @Autowired JobStages stages;
    @Autowired ObjectMapper mapper;

    MediaPersistenceService.Ensure upload;

    JobLease stagedClaim(String name) throws Exception {
        byte[] content = MediaFixtures.bytes(name);
        upload = queued(content);
        stage(upload, content);
        return claim();
    }

    /** Waits out the retry delay a worker would have scheduled, then claims the next attempt. */
    JobLease nextClaim() throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            var next = leases.claim(Duration.ofSeconds(30));
            if (next.isPresent()) { return next.get(); }
            Thread.sleep(20);
        }
        throw new AssertionError("No claimable attempt");
    }

    static String prefix(MediaPersistenceService.Ensure upload, int attempt) {
        return "hls/" + upload.uploadId() + "/attempt-" + attempt;
    }

    List<String> hlsKeys(String prefix) {
        return client.listObjectsV2(ListObjectsV2Request.builder().bucket(HLS).prefix(prefix).build()).contents()
                .stream().map(object -> object.key()).sorted().toList();
    }

    byte[] hlsBytes(String key) {
        return client.getObjectAsBytes(request -> request.bucket(HLS).key(key)).asByteArray();
    }

    String hlsText(String key) { return new String(hlsBytes(key), StandardCharsets.UTF_8); }

    /** Every object under a prefix by key, with the SHA-256 of its stored bytes: an exact generation snapshot. */
    Map<String, String> generation(String prefix) {
        var snapshot = new TreeMap<String, String>();
        for (var key : hlsKeys(prefix + "/")) { snapshot.put(key, sha(hlsBytes(key))); }
        return snapshot;
    }

    List<String> events() {
        return jdbc.queryForList("SELECT payload->>'state' FROM media_outbox_events WHERE asset_id = ? "
                + "ORDER BY aggregate_version", String.class, upload.assetId());
    }

    /** Every outbox column of this asset's events, in aggregate order. */
    List<Map<String, Object>> outbox() {
        return jdbc.queryForList("""
                SELECT event_id, aggregate_version, correlation_id, payload::text AS payload, delivery_state,
                    created_at FROM media_outbox_events WHERE asset_id = ? ORDER BY aggregate_version
                """, upload.assetId());
    }

    Map<String, Object> asset() {
        return jdbc.queryForMap("SELECT * FROM media_assets WHERE asset_id = ?", upload.assetId());
    }

    Map<String, Object> job() {
        return jdbc.queryForMap("SELECT * FROM media_jobs WHERE upload_id = ?", upload.uploadId());
    }

    List<Path> workspaceDirectories() throws IOException {
        try (var tree = Files.walk(configured.scratchDirectory())) {
            return tree.filter(path -> Files.isDirectory(path)
                    && path.getFileName().toString().startsWith("hls-attempt-")).toList();
        }
    }

    void assertNotReady() {
        var asset = asset();
        assertThat(asset.get("state")).isEqualTo("PROCESSING");
        assertThat(asset.get("output_prefix")).isNull();
        assertThat(asset.get("master_manifest_key")).isNull();
        assertThat(asset.get("duration_seconds")).isNull();
        assertThat(asset.get("aggregate_version")).isEqualTo(1L);
        assertThat(events()).containsExactly("PROCESSING");
    }

    /**
     * The gate invariant for one asset: READY points at the given attempt's prefix, exactly one READY event exists, it
     * is the asset's current aggregate version and carries its duration, and the job is spent.
     */
    void assertSingleReady(int attempt) throws Exception {
        var asset = asset();
        assertThat(asset.get("state")).isEqualTo("READY");
        assertThat(asset.get("output_prefix")).isEqualTo(prefix(upload, attempt));
        assertThat(asset.get("master_manifest_key")).isEqualTo(prefix(upload, attempt) + "/master.m3u8");
        assertThat(asset.get("aggregate_version")).isEqualTo(2L);
        assertThat(events()).containsExactly("PROCESSING", "READY");
        var ready = outbox().getLast();
        assertThat(ready.get("aggregate_version")).isEqualTo(asset.get("aggregate_version"));
        assertThat(mapper.readTree((String) ready.get("payload")).get("durationSeconds").asInt())
                .isEqualTo(asset.get("duration_seconds"));
        var job = job();
        assertThat(job.get("stage")).isEqualTo("SUCCEEDED");
        assertThat(job.get("attempt_count")).isEqualTo(attempt);
        assertThat(job.get("lease_token")).isNull();
        // The selected generation is complete: the master, the variant and every segment the variant names.
        String prefix = prefix(upload, attempt);
        var named = hlsText(prefix + "/rendition.m3u8").lines().filter(line -> !line.startsWith("#"))
                .map(name -> prefix + "/" + name);
        assertThat(hlsKeys(prefix + "/")).containsExactlyInAnyOrderElementsOf(
                java.util.stream.Stream.concat(java.util.stream.Stream.of(prefix + "/master.m3u8",
                        prefix + "/rendition.m3u8"), named).toList());
    }
}
