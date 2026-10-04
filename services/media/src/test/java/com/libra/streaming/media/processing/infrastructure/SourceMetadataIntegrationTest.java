package com.libra.streaming.media.processing.infrastructure;

import com.libra.streaming.media.processing.application.JobLeases;
import com.libra.streaming.media.processing.application.JobSources;
import com.libra.streaming.media.processing.application.SourceMetadataStore;
import com.libra.streaming.media.processing.domain.JobLease;
import com.libra.streaming.media.processing.domain.SourceMetadata;
import com.libra.streaming.media.upload.infrastructure.MediaPersistenceService;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.assertj.core.api.Assertions.*;

/** Real PostgreSQL: probed metadata is fenced by the lease, bound to the selected source, and policy-checked. */
@Testcontainers
@SpringBootTest(properties = "libra.media.storage.enabled=false")
class SourceMetadataIntegrationTest {
    private static final String KEY = "sources/%s/attempt-%d/source.mp4";

    @Container static final org.testcontainers.postgresql.PostgreSQLContainer POSTGRES =
            new org.testcontainers.postgresql.PostgreSQLContainer("postgres:18.6-alpine")
                    .withDatabaseName("media_metadata_test").withUsername("media_metadata_test")
                    .withPassword(UUID.randomUUID().toString());

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired JobLeases leases;
    @Autowired JobSources sources;
    @Autowired SourceMetadataStore store;
    @Autowired MediaPersistenceService uploads;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach void clean() { jdbc.execute("TRUNCATE media_assets CASCADE"); }

    private static SourceMetadata audible() {
        return new SourceMetadata(10_016, 1920, 1080, 90, 1080, 1920, 30000, 1001, "High",
                new SourceMetadata.Audio(2, 48000));
    }

    private static SourceMetadata silent() {
        return new SourceMetadata(2_000, 320, 180, 0, 320, 180, 24, 1, "Main", null);
    }

    @Test
    void currentOwnerRecordsAndReadsBackTheValidatedMetadataOfTheSelectedSource() {
        var lease = selected();
        assertThat(store.find(lease)).isEmpty();
        long aggregate = aggregateVersion(lease);

        assertThat(store.record(lease, key(lease), audible())).isTrue();

        assertThat(store.find(lease)).contains(audible());
        assertThat(jdbc.queryForObject("SELECT source_key FROM media_source_metadata WHERE asset_id = ?",
                String.class, lease.assetId())).isEqualTo(key(lease));
        // Probing is not readiness and not an editorial event.
        assertThat(aggregateVersion(lease)).isEqualTo(aggregate);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM media_outbox_events", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT stage FROM media_jobs WHERE id = ?", String.class, lease.jobId()))
                .isEqualTo("SOURCE_SELECTED");
    }

    @Test
    void aSilentSourceKeepsNoAudioColumns() {
        var lease = selected();
        assertThat(store.record(lease, key(lease), silent())).isTrue();
        assertThat(store.find(lease)).contains(silent());
        assertThat(jdbc.queryForObject("SELECT audio_channels IS NULL AND audio_sample_rate IS NULL "
                + "FROM media_source_metadata", Boolean.class)).isTrue();
    }

    @Test
    void recordingTheSameFactsAgainIsIdempotentButDifferentFactsAreRefused() {
        var lease = selected();
        assertThat(store.record(lease, key(lease), audible())).isTrue();
        assertThat(store.record(lease, key(lease), audible())).isTrue();
        assertThatThrownBy(() -> store.record(lease, key(lease), silent()))
                .isInstanceOf(InvalidDataAccessApiUsageException.class).hasMessage("Source metadata is immutable")
                .hasCauseInstanceOf(IllegalStateException.class);
        assertThat(store.find(lease)).contains(audible());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM media_source_metadata", Integer.class)).isOne();
    }

    @Test
    void metadataMustDescribeTheSelectedSourceNotAnotherKeyOrNone() {
        queued();
        var lease = leases.claim(Duration.ofSeconds(30)).orElseThrow();
        assertThatThrownBy(() -> store.record(lease, key(lease), silent()))
                .isInstanceOf(InvalidDataAccessApiUsageException.class).hasCauseInstanceOf(IllegalStateException.class);
        assertThat(sources.select(lease, key(lease))).isTrue();
        assertThatThrownBy(() -> store.record(lease, "sources/" + UUID.randomUUID() + "/attempt-1/source.mp4", silent()))
                .isInstanceOf(InvalidDataAccessApiUsageException.class).hasCauseInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM media_source_metadata", Integer.class)).isZero();
    }

    @Test
    void invalidKeysAreRejectedBeforeAnyWrite() {
        var lease = selected();
        for (String invalid : new String[] {null, "", "  ", "x".repeat(513)}) {
            assertThatThrownBy(() -> store.record(lease, invalid, silent()))
                    .isInstanceOf(InvalidDataAccessApiUsageException.class)
                    .hasCauseInstanceOf(IllegalArgumentException.class);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM media_source_metadata", Integer.class)).isZero();
    }

    @Test
    void anExpiredOrReclaimedOwnerCannotRecordOrRead() {
        var old = selected();
        expire(old);
        assertThat(store.record(old, key(old), silent())).isFalse();
        assertThat(store.find(old)).isEmpty();
        var successor = leases.claim(Duration.ofSeconds(30)).orElseThrow();
        assertThat(successor.attempt()).isEqualTo(2);
        assertThat(store.record(old, key(old), silent())).isFalse();
        assertThat(store.find(old)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM media_source_metadata", Integer.class)).isZero();
    }

    @Test
    void aRecordedProbeSurvivesTheOwnerAndIsVisibleToTheNextClaim() {
        var first = selected();
        assertThat(store.record(first, key(first), audible())).isTrue();
        expire(first);
        var second = leases.claim(Duration.ofSeconds(30)).orElseThrow();
        assertThat(second.attempt()).isEqualTo(2);
        // The facts belong to the asset's selected source, not to the claim that measured them.
        assertThat(store.find(second)).contains(audible());
        assertThat(store.find(first)).isEmpty();
    }

    @Test
    void theTableRepeatsThePolicyAsALastLineOfDefence() {
        var lease = selected();
        record Row(String column, Object value) {}
        for (var bad : new Row[] {new Row("coded_width", 3840), new Row("display_width", 1921),
                new Row("display_height", 8), new Row("rotation", 45), new Row("duration_millis", 600_001),
                new Row("duration_millis", 0), new Row("frame_rate_numerator", 31), new Row("frame_rate_numerator", 0),
                new Row("video_profile", "High 10"), new Row("audio_channels", 9)}) {
            assertThatThrownBy(() -> insertWith(lease, bad.column(), bad.value()))
                    .as(bad.column() + "=" + bad.value()).isInstanceOf(DataIntegrityViolationException.class);
        }
        // Audio is all or nothing.
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO media_source_metadata (asset_id, asset_version, source_key, duration_millis, video_profile,
                    coded_width, coded_height, rotation, display_width, display_height, frame_rate_numerator,
                    frame_rate_denominator, audio_channels, audio_sample_rate, created_at)
                VALUES (?, 1, ?, 1000, 'Main', 320, 180, 0, 320, 180, 24, 1, 2, NULL, now())
                """, lease.assetId(), key(lease))).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM media_source_metadata", Integer.class)).isZero();
        // The boundary values themselves are accepted.
        assertThatCode(() -> insertWith(lease, "frame_rate_numerator", 30)).doesNotThrowAnyException();
    }

    /** Inserts one otherwise valid 1080p30 row with a single column replaced. */
    private void insertWith(JobLease lease, String column, Object value) {
        var values = new java.util.LinkedHashMap<String, Object>();
        values.put("asset_id", lease.assetId());
        values.put("asset_version", 1);
        values.put("source_key", key(lease));
        values.put("duration_millis", 1000);
        values.put("video_profile", "Main");
        values.put("coded_width", 1920);
        values.put("coded_height", 1080);
        values.put("rotation", 0);
        values.put("display_width", 1920);
        values.put("display_height", 1080);
        values.put("frame_rate_numerator", 30);
        values.put("frame_rate_denominator", 1);
        values.put("audio_channels", null);
        values.put("audio_sample_rate", null);
        values.put(column, value);
        jdbc.update("INSERT INTO media_source_metadata (" + String.join(", ", values.keySet()) + ", created_at) VALUES ("
                + "?, ".repeat(values.size()) + "now())", values.values().toArray());
    }

    private JobLease selected() {
        queued();
        var lease = leases.claim(Duration.ofSeconds(30)).orElseThrow();
        assertThat(sources.select(lease, key(lease))).isTrue();
        return lease;
    }

    private static String key(JobLease lease) { return KEY.formatted(lease.uploadId(), lease.attempt()); }

    private long aggregateVersion(JobLease lease) {
        return jdbc.queryForObject("SELECT aggregate_version FROM media_assets WHERE asset_id = ?", Long.class,
                lease.assetId());
    }

    private void queued() {
        var upload = new MediaPersistenceService.Ensure(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), 1, 1024, "a".repeat(64), Instant.now().plusSeconds(1800));
        uploads.ensure(upload);
        uploads.queue(upload.uploadId(), upload.assetId(), 1);
    }

    private void expire(JobLease lease) {
        jdbc.update("UPDATE media_jobs SET lease_until = clock_timestamp() - INTERVAL '1 second' WHERE id = ?",
                lease.jobId());
    }
}
