package com.libra.streaming.media.lifecycle.infrastructure;

import com.libra.streaming.media.lifecycle.application.LifecycleEvents;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Processing and lifecycle state as gauges read from PostgreSQL when the registry is sampled (so they hold across
 * restarts and whichever process does the work), plus counters for what this process's sweeper did. A failed read
 * is NaN, never a reassuring zero. Every label is a fixed value: no upload, job, asset, key or error text.
 */
@Component
public class LifecycleMetrics implements LifecycleEvents {
    static final List<String> FAILURE_CODES = List.of("EXPIRY_UNAVAILABLE", "CLEANUP_DATABASE_UNAVAILABLE",
            "CLEANUP_STORAGE_UNAVAILABLE", "CLEANUP_FAILED", "SHUTDOWN_TIMEOUT");
    static final List<String> TERMINAL_CODES = List.of("SOURCE_MISSING", "SIZE_MISMATCH", "CHECKSUM_MISMATCH",
            "UNSUPPORTED_MEDIA", "CORRUPT_INPUT", "PROCESSING_FAILED", "RETRY_EXHAUSTED");
    private static final Logger LOG = LoggerFactory.getLogger(LifecycleMetrics.class);
    private static final String ACTIVE = "('CLAIMED', 'SOURCE_SELECTED', 'TRANSCODING', 'FINALIZING')";
    private static final String UNFINISHED = "('QUEUED', 'CLAIMED', 'SOURCE_SELECTED', 'TRANSCODING', 'FINALIZING')";

    private final JdbcTemplate jdbc;
    private final Counter expired;
    private final Counter deleted;
    private final Counter cleaned;
    private final Map<String, Counter> failures;

    public LifecycleMetrics(JdbcTemplate jdbc, MeterRegistry registry) {
        this.jdbc = jdbc;
        gauges(registry, "libra.media.jobs", "Media jobs by stage group", "stage", Map.of(
                "queued", "SELECT count(*) FROM media_jobs WHERE stage = 'QUEUED' AND failure_code IS NULL",
                "retrying", "SELECT count(*) FROM media_jobs WHERE stage = 'QUEUED' AND failure_code IS NOT NULL",
                "processing", "SELECT count(*) FROM media_jobs WHERE stage IN " + ACTIVE,
                "succeeded", "SELECT count(*) FROM media_jobs WHERE stage = 'SUCCEEDED'",
                "failed", "SELECT count(*) FROM media_jobs WHERE stage = 'FAILED_PERMANENT'",
                "exhausted", "SELECT count(*) FROM media_jobs WHERE stage = 'EXHAUSTED'"));
        gauges(registry, "libra.media.jobs.unfinished", "Unfinished Media jobs by attempts spent", "attempts",
                List.of("0", "1", "2", "3").stream().collect(Collectors.toMap(Function.identity(),
                        attempts -> "SELECT count(*) FROM media_jobs WHERE stage IN " + UNFINISHED
                                + " AND attempt_count = " + attempts)));
        gauges(registry, "libra.media.jobs.terminal", "Terminally failed Media jobs by failure code", "failure",
                TERMINAL_CODES.stream().collect(Collectors.toMap(Function.identity(),
                        code -> "SELECT count(*) FROM media_jobs WHERE stage IN ('FAILED_PERMANENT', 'EXHAUSTED') "
                                + "AND failure_code = '" + code + "'")));
        seconds(registry, "libra.media.jobs.queue.age", "How long the oldest due job has waited", """
                SELECT COALESCE(EXTRACT(EPOCH FROM clock_timestamp() - min(next_attempt_at)), 0)
                FROM media_jobs WHERE stage = 'QUEUED' AND next_attempt_at <= clock_timestamp()
                """);
        seconds(registry, "libra.media.jobs.unfinished.age", "Age of the oldest unfinished job since admission",
                "SELECT COALESCE(EXTRACT(EPOCH FROM clock_timestamp() - min(created_at)), 0) FROM media_jobs "
                        + "WHERE stage IN " + UNFINISHED);
        seconds(registry, "libra.media.jobs.duration.max", "Longest admission-to-READY time in the last hour", """
                SELECT COALESCE(EXTRACT(EPOCH FROM max(updated_at - created_at)), 0) FROM media_jobs
                WHERE stage = 'SUCCEEDED' AND updated_at > clock_timestamp() - INTERVAL '1 hour'
                """);
        gauges(registry, "libra.media.uploads", "Uploads the lifecycle sweeper has acted on or still must",
                "state", Map.of(
                        "abandoned", "SELECT count(*) FROM media_uploads WHERE state = 'OPEN' "
                                + "AND expires_at <= clock_timestamp()",
                        "expired", "SELECT count(*) FROM media_uploads WHERE state = 'EXPIRED'"));
        // Settled but not yet cleaned, whatever the grace: the orphan backlog, uploads still in their grace included.
        Gauge.builder("libra.media.cleanup.pending", this, metrics -> metrics.read("""
                SELECT count(*) FROM media_uploads u LEFT JOIN media_jobs j ON j.upload_id = u.id
                WHERE u.objects_cleaned_at IS NULL AND (u.state = 'EXPIRED'
                    OR (u.state = 'SUBMITTED' AND j.stage IN ('SUCCEEDED', 'FAILED_PERMANENT', 'EXHAUSTED')))
                """)).description("Settled uploads whose leftover objects are not yet cleaned").register(registry);
        expired = Counter.builder("libra.media.lifecycle.expired").description("Uploads this process expired")
                .register(registry);
        deleted = Counter.builder("libra.media.cleanup.deleted").description("Objects this process deleted")
                .register(registry);
        cleaned = Counter.builder("libra.media.cleanup.cleaned")
                .description("Uploads this process recorded as cleaned").register(registry);
        failures = FAILURE_CODES.stream().collect(Collectors.toUnmodifiableMap(Function.identity(),
                code -> Counter.builder("libra.media.lifecycle.failures").tag("failure", code)
                        .description("Lifecycle steps that failed").register(registry)));
    }

    @Override public void expired(int uploads) { expired.increment(uploads); }
    @Override public void deleted(int objects) { deleted.increment(objects); }
    @Override public void cleaned() { cleaned.increment(); }

    @Override
    public void failed(String code) {
        var counter = failures.get(code);
        if (counter == null) { throw new IllegalArgumentException("Unknown lifecycle failure"); }
        counter.increment();
        LOG.warn("Media lifecycle: {}", code);
    }

    private void gauges(MeterRegistry registry, String name, String description, String tag,
            Map<String, String> queries) {
        queries.forEach((value, sql) -> Gauge.builder(name, this, metrics -> metrics.read(sql)).tag(tag, value)
                .description(description).register(registry));
    }

    private void seconds(MeterRegistry registry, String name, String description, String sql) {
        Gauge.builder(name, this, metrics -> metrics.read(sql)).description(description).baseUnit("seconds")
                .register(registry);
    }

    private double read(String sql) {
        try {
            Number value = jdbc.queryForObject(sql, Number.class);
            return value == null ? Double.NaN : value.doubleValue();
        } catch (RuntimeException exception) {
            return Double.NaN;
        }
    }
}
