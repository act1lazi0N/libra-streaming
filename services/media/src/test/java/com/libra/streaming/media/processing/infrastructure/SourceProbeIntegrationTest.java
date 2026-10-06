package com.libra.streaming.media.processing.infrastructure;

import com.libra.streaming.media.processing.application.SourceMetadataStore;
import com.libra.streaming.media.processing.application.SourceProber;
import com.libra.streaming.media.processing.domain.JobLease;
import com.libra.streaming.media.processing.domain.ProcessingFailure;
import com.libra.streaming.media.processing.domain.SourceMetadata;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import static org.assertj.core.api.Assertions.*;

/**
 * The whole stage with nothing mocked: real SeaweedFS holds staging and the frozen copy, real PostgreSQL holds the
 * lease and the metadata, and the real ffprobe measures a generated fixture. No transcode exists, so an accepted
 * source ends as "probed" and the asset stays PROCESSING.
 */
class SourceProbeIntegrationTest extends SourceStorageFixture {
    @DynamicPropertySource
    static void probe(DynamicPropertyRegistry registry) {
        registry.add("libra.media.probe.executable", () -> FfprobeLocator.locate().toString());
        registry.add("libra.media.probe.timeout", () -> "30s");
    }

    @Autowired SourceProber prober;
    @Autowired SourceMetadataStore metadata;

    private String scratchListing() throws IOException {
        try (var files = java.nio.file.Files.walk(configured.scratchDirectory())) {
            return files.filter(java.nio.file.Files::isRegularFile).map(path -> path.getFileName() + ":"
                    + path.toFile().length()).toList().toString();
        }
    }

    private static byte[] fixture(String name) throws IOException { return MediaFixtures.bytes(name); }

    private JobLease stagedClaim(String name, byte[][] holder, MediaPersistenceHolder upload) throws Exception {
        byte[] content = fixture(name);
        holder[0] = content;
        upload.value = queued(content);
        stage(upload.value, content);
        return claim();
    }

    /** Small box so a helper can hand the queued upload back to the test. */
    private static final class MediaPersistenceHolder {
        com.libra.streaming.media.upload.infrastructure.MediaPersistenceService.Ensure value;
    }

    @Test
    void anAcceptableSourceIsFrozenProbedAndItsMetadataCommittedUnderTheLease() throws Exception {
        var upload = new MediaPersistenceHolder();
        var content = new byte[1][];
        var lease = stagedClaim("valid-1080p-aac.mp4", content, upload);
        long before = scratchFiles();

        var result = prober.probe(lease, () -> false);

        assertThat(result).isInstanceOf(SourceProber.Result.Probed.class);
        var probed = ((SourceProber.Result.Probed) result).metadata();
        assertThat(probed.displayWidth()).isEqualTo(1920);
        assertThat(probed.displayHeight()).isEqualTo(1080);
        assertThat(probed.audio()).isEqualTo(new SourceMetadata.Audio(2, 48000));
        assertThat(metadata.find(lease)).contains(probed);
        assertThat(frozen(attemptKey(upload.value, 1))).isEqualTo(content[0]);
        assertThat(selectedKey(upload.value)).isEqualTo(attemptKey(upload.value, 1));
        assertThat(jdbc.queryForObject("SELECT source_key FROM media_source_metadata WHERE asset_id = ?",
                String.class, upload.value.assetId())).isEqualTo(attemptKey(upload.value, 1));
        // Probed is not ready: no READY state, no event, and the job has not advanced past the source.
        assertThat(jdbc.queryForObject("SELECT state FROM media_assets WHERE asset_id = ?", String.class,
                upload.value.assetId())).isEqualTo("PROCESSING");
        assertThat(jdbc.queryForObject("SELECT stage FROM media_jobs WHERE id = ?", String.class, lease.jobId()))
                .isEqualTo("SOURCE_SELECTED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM media_outbox_events", Integer.class)).isZero();
        assertThat(scratchFiles()).isEqualTo(before);
    }

    @Test
    void aRotatedSilentPhoneStyleSourceIsNormalizedBeforeItIsStored() throws Exception {
        var upload = new MediaPersistenceHolder();
        var lease = stagedClaim("valid-rotated-90.mp4", new byte[1][], upload);

        var probed = ((SourceProber.Result.Probed) prober.probe(lease, () -> false)).metadata();

        assertThat(probed.rotation()).isIn(90, 270);
        assertThat(probed.displayWidth()).isEqualTo(720);
        assertThat(probed.displayHeight()).isEqualTo(1280);
        assertThat(jdbc.queryForObject("SELECT display_width FROM media_source_metadata", Integer.class))
                .isEqualTo(720);
    }

    @Test
    void unsupportedAndCorruptInputsFailPermanentlyWithNoMetadataAndNothingBeyondTheFrozenSource() throws Exception {
        record Case(String fixture, ProcessingFailure expected) {}
        for (var rejected : java.util.List.of(new Case("invalid-hevc.mp4", ProcessingFailure.UNSUPPORTED_MEDIA),
                new Case("invalid-4k.mp4", ProcessingFailure.UNSUPPORTED_MEDIA),
                new Case("invalid-cover-art.mp4", ProcessingFailure.UNSUPPORTED_MEDIA),
                new Case("corrupt-truncated.mp4", ProcessingFailure.CORRUPT_INPUT),
                new Case("corrupt-garbage.bin", ProcessingFailure.CORRUPT_INPUT))) {
            var upload = new MediaPersistenceHolder();
            var lease = stagedClaim(rejected.fixture(), new byte[1][], upload);

            var result = prober.probe(lease, () -> false);

            assertThat(result).as(rejected.fixture()).isInstanceOf(SourceProber.Result.Rejected.class);
            var outcome = ((SourceProber.Result.Rejected) result).outcome();
            assertThat(outcome.failure()).as(rejected.fixture()).isEqualTo(rejected.expected());
            assertThat(outcome.permanent()).as(rejected.fixture()).isTrue();
            assertThat(metadata.find(lease)).as(rejected.fixture()).isEmpty();
            // The bytes were proven and frozen first; the rejection leaves that private copy as an orphan.
            assertThat(frozenObjects(upload.value)).as(rejected.fixture()).containsExactly(attemptKey(upload.value, 1));
            assertThat(leases.fail(lease, outcome.failure())).isTrue();
            assertThat(jdbc.queryForObject("SELECT state FROM media_assets WHERE asset_id = ?", String.class,
                    upload.value.assetId())).as(rejected.fixture()).isEqualTo("FAILED");
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM media_source_metadata", Integer.class)).isZero();
    }

    @Test
    void hostileInputsEndInOneControlledFailureWithoutFloodOrReadiness() throws Exception {
        record Case(String fixture, ProcessingFailure expected) {}
        for (var hostile : java.util.List.of(new Case("invalid-lying-headers.mp4", ProcessingFailure.CORRUPT_INPUT),
                new Case("invalid-track-lengths.mp4", ProcessingFailure.UNSUPPORTED_MEDIA),
                new Case("invalid-seventy-streams.mp4", ProcessingFailure.UNSUPPORTED_MEDIA),
                new Case("invalid-huge-metadata.mp4", ProcessingFailure.CORRUPT_INPUT),
                new Case("invalid-8k.mp4", ProcessingFailure.UNSUPPORTED_MEDIA),
                new Case("invalid-rotated-45.mp4", ProcessingFailure.UNSUPPORTED_MEDIA),
                new Case("invalid-brand-spoofed-tag.mp4", ProcessingFailure.UNSUPPORTED_MEDIA),
                new Case("invalid-container.mkv", ProcessingFailure.CORRUPT_INPUT),
                new Case("corrupt-ftyp-only.mp4", ProcessingFailure.CORRUPT_INPUT),
                new Case("corrupt-truncated-early.mp4", ProcessingFailure.CORRUPT_INPUT))) {
            var upload = new MediaPersistenceHolder();
            var lease = stagedClaim(hostile.fixture(), new byte[1][], upload);
            int jobs = jdbc.queryForObject("SELECT count(*) FROM media_jobs", Integer.class);
            long scratch = scratchFiles();

            var result = prober.probe(lease, () -> false);

            assertThat(result).as(hostile.fixture()).isInstanceOf(SourceProber.Result.Rejected.class);
            var outcome = ((SourceProber.Result.Rejected) result).outcome();
            assertThat(outcome.failure()).as(hostile.fixture()).isEqualTo(hostile.expected());
            assertThat(outcome.permanent()).as(hostile.fixture()).isTrue();
            // One verdict, nothing queued behind it, nothing ready, nothing left in scratch.
            assertThat(jdbc.queryForObject("SELECT count(*) FROM media_jobs", Integer.class)).as(hostile.fixture())
                    .isEqualTo(jobs);
            assertThat(jdbc.queryForObject("SELECT state FROM media_assets WHERE asset_id = ?", String.class,
                    upload.value.assetId())).as(hostile.fixture()).isEqualTo("PROCESSING");
            assertThat(metadata.find(lease)).as(hostile.fixture()).isEmpty();
            assertThat(scratchFiles()).as(hostile.fixture() + " " + scratchListing()).isEqualTo(scratch);
            assertThat(leases.fail(lease, outcome.failure())).isTrue();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM media_source_metadata", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM media_outbox_events", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM media_assets WHERE state = 'READY'", Integer.class))
                .isZero();
    }

    @Test
    void aRetryAfterASuccessfulProbeReusesTheRecordedFactsEvenWithStagingGone() throws Exception {
        var upload = new MediaPersistenceHolder();
        var first = stagedClaim("valid-160x96-silent.mp4", new byte[1][], upload);
        var probed = prober.probe(first, () -> false);
        assertThat(probed).isInstanceOf(SourceProber.Result.Probed.class);
        var createdAt = jdbc.queryForObject("SELECT created_at FROM media_source_metadata", java.sql.Timestamp.class);
        assertThat(leases.release(first)).isTrue();
        client.deleteObject(DeleteObjectRequest.builder().bucket(BUCKET).key(stagingKey(upload.value)).build());

        var second = claim();
        var again = prober.probe(second, () -> false);

        assertThat(second.attempt()).isEqualTo(2);
        assertThat(again).isEqualTo(probed);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM media_source_metadata", Integer.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT created_at FROM media_source_metadata", java.sql.Timestamp.class))
                .isEqualTo(createdAt);
        assertThat(frozenObjects(upload.value)).containsExactly(attemptKey(upload.value, 1));
    }

    @Test
    void aRetryAfterARejectionReachesTheSameVerdictFromTheFrozenSourceAlone() throws Exception {
        var upload = new MediaPersistenceHolder();
        var first = stagedClaim("invalid-hevc.mp4", new byte[1][], upload);
        var rejected = prober.probe(first, () -> false);
        assertThat(rejected).isInstanceOf(SourceProber.Result.Rejected.class);
        assertThat(leases.release(first)).isTrue();
        client.deleteObject(DeleteObjectRequest.builder().bucket(BUCKET).key(stagingKey(upload.value)).build());

        var second = claim();
        var again = prober.probe(second, () -> false);

        assertThat(again).isEqualTo(rejected);
        assertThat(selectedKey(upload.value)).isEqualTo(attemptKey(upload.value, 1));
        assertThat(frozenObjects(upload.value)).containsExactly(attemptKey(upload.value, 1));
    }

    @Test
    void aLostLeaseNeverRecordsMetadata() throws Exception {
        var upload = new MediaPersistenceHolder();
        var lease = stagedClaim("valid-160x96-silent.mp4", new byte[1][], upload);
        long before = scratchFiles();
        expire(lease);

        var result = prober.probe(lease, () -> false);

        assertThat(result).isInstanceOf(SourceProber.Result.LeaseLost.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM media_source_metadata", Integer.class)).isZero();
        assertThat(scratchFiles()).isEqualTo(before);
    }
}
