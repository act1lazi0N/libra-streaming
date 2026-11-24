package com.libra.streaming.recommendation.statistics.infrastructure;

import com.libra.streaming.recommendation.freshness.application.ProjectionFreshness;
import com.libra.streaming.recommendation.statistics.application.ConsistentReads;
import com.libra.streaming.recommendation.statistics.application.PlatformStatistics;
import com.libra.streaming.recommendation.statistics.application.StatisticsStore;
import java.util.function.Supplier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
public class StatisticsConfiguration {
    static final int STATEMENT_TIMEOUT_MS = 2000;

    @Bean
    StatisticsStore statisticsStore(JdbcTemplate jdbc) { return new JdbcStatisticsStore(jdbc); }

    @Bean
    PlatformStatistics platformStatistics(StatisticsStore store, ProjectionFreshness freshness,
            PlatformTransactionManager transactions, JdbcTemplate jdbc) {
        return new PlatformStatistics(store, freshness, snapshotReads(transactions, jdbc));
    }

    /**
     * Read-only, REPEATABLE READ: PostgreSQL takes the snapshot at the first statement and every later statement of
     * the transaction sees exactly that snapshot, so the report's parts cannot disagree. Read-only reads in
     * PostgreSQL never fail with a serialization error.
     *
     * <p>The report reads facts directly over up to 31 days. Core gives up after 500 ms and shows UNAVAILABLE, but
     * PostgreSQL would keep running an abandoned statement, so repeated retries could pile up load: every statement
     * of the snapshot is cancelled after {@link #STATEMENT_TIMEOUT_MS} (the failure becomes 503
     * ANALYTICS_UNAVAILABLE).
     */
    static ConsistentReads snapshotReads(PlatformTransactionManager transactions, JdbcTemplate jdbc) {
        var template = new TransactionTemplate(transactions);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        template.setReadOnly(true);
        template.setName("statistics-report");
        return new ConsistentReads() {
            @Override public <T> T read(Supplier<T> work) {
                return template.execute(status -> {
                    jdbc.execute("SET LOCAL statement_timeout = " + STATEMENT_TIMEOUT_MS);
                    return work.get();
                });
            }
        };
    }
}
