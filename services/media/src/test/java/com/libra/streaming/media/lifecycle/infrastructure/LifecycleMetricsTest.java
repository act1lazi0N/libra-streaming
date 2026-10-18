package com.libra.streaming.media.lifecycle.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

class LifecycleMetricsTest {
    @Test
    void anUnreadableDatabaseIsNaNNeverZero() {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Number.class)))
                .thenThrow(new DataAccessResourceFailureException("connection refused to db.internal:5432"));
        var registry = new SimpleMeterRegistry();
        new LifecycleMetrics(jdbc, registry);
        var gauges = registry.getMeters().stream().filter(meter -> meter.getId().getType() == Meter.Type.GAUGE)
                .toList();
        assertThat(gauges).hasSizeGreaterThanOrEqualTo(20);
        assertThat(gauges).allSatisfy(gauge -> assertThat(registry.get(gauge.getId().getName())
                .tags(gauge.getId().getTags()).gauge().value()).isNaN());
    }

    @Test
    void labelsAreAFixedLowCardinalitySet() {
        var registry = new SimpleMeterRegistry();
        var metrics = new LifecycleMetrics(mock(JdbcTemplate.class), registry);
        for (var code : LifecycleMetrics.FAILURE_CODES) { metrics.failed(code); }
        metrics.deleted(3);
        metrics.expired(2);
        metrics.cleaned();
        assertThatThrownBy(() -> metrics.failed("s3://libra-source/staging/secret")).isInstanceOf(
                IllegalArgumentException.class);
        var labels = registry.getMeters().stream().flatMap(meter -> meter.getId().getTags().stream())
                .map(tag -> tag.getKey() + "=" + tag.getValue()).distinct().toList();
        assertThat(labels).hasSizeLessThanOrEqualTo(30).allSatisfy(label -> assertThat(label)
                .matches("(stage|attempts|failure|state)=[A-Za-z0-9_]+"));
        assertThat(registry.get("libra.media.cleanup.deleted").counter().count()).isEqualTo(3);
        assertThat(registry.get("libra.media.lifecycle.expired").counter().count()).isEqualTo(2);
        assertThat(registry.get("libra.media.lifecycle.failures").tag("failure", "CLEANUP_FAILED").counter()
                .count()).isEqualTo(1);
    }
}
