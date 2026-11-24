package com.libra.streaming.recommendation.statistics.infrastructure;

import com.libra.streaming.recommendation.internalapi.infrastructure.InternalApiFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "libra.analytics.consumers.enabled=false",
        "libra.analytics.freshness.checkpoints.enabled=false",
        "libra.analytics.bootstrap.enabled=false",
        "libra.analytics.dead-letters.redrive-on-startup=false"
})
@DirtiesContext
class StatisticsSnapshotReadsIntegrationTest extends InternalApiFixture {
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    void snapshotIsRepeatableReadAndDatabaseReadOnly() {
        var settings = StatisticsConfiguration.snapshotReads(transactionManager, jdbc).read(() ->
                jdbc.queryForMap("SELECT current_setting('transaction_isolation') AS isolation, "
                        + "current_setting('transaction_read_only') AS read_only, "
                        + "current_setting('statement_timeout') AS timeout"));
        assertThat(settings).containsEntry("isolation", "repeatable read").containsEntry("read_only", "on")
                .containsEntry("timeout", "2s");
        // SET LOCAL ends with the transaction: pooled connections keep no timeout.
        assertThat(jdbc.queryForObject("SELECT current_setting('statement_timeout')", String.class)).isEqualTo("0");
    }

    @Test
    void slowStatementIsCancelledAfterTwoSeconds() {
        long started = System.nanoTime();
        assertThatThrownBy(() -> StatisticsConfiguration.snapshotReads(transactionManager, jdbc).read(() ->
                jdbc.queryForObject("SELECT pg_sleep(10)::text", String.class)))
                .isInstanceOf(DataAccessException.class)
                .satisfies(failure -> assertThat(((java.sql.SQLException) failure.getCause()).getSQLState())
                        .isEqualTo("57014"));
        assertThat(java.time.Duration.ofNanos(System.nanoTime() - started)).isLessThan(java.time.Duration.ofSeconds(6));
    }

    @Test
    void databaseRejectsWriteInsideSnapshot() {
        var before = jdbc.queryForList("SELECT * FROM input_checkpoints ORDER BY topic");
        assertThatThrownBy(() -> StatisticsConfiguration.snapshotReads(transactionManager, jdbc).read(() ->
                jdbc.update("UPDATE input_checkpoints SET confirmed_at = confirmed_at WHERE false")))
                .isInstanceOf(DataAccessException.class)
                .satisfies(failure -> assertThat(((java.sql.SQLException) failure.getCause()).getSQLState())
                        .isEqualTo("25006"));
        assertThat(jdbc.queryForList("SELECT * FROM input_checkpoints ORDER BY topic")).isEqualTo(before);
    }
}
