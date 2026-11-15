package com.libra.streaming.recommendation.freshness.infrastructure;

import com.libra.streaming.recommendation.freshness.application.CheckpointStore;
import com.libra.streaming.recommendation.freshness.application.ProjectionFreshness;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
public class FreshnessConfiguration {
    @Bean
    CheckpointStore checkpointStore(JdbcTemplate jdbc) { return new JdbcCheckpointStore(jdbc); }

    @Bean
    ProjectionFreshness projectionFreshness(CheckpointStore store, Clock clock) {
        return new ProjectionFreshness(store, clock);
    }
}
