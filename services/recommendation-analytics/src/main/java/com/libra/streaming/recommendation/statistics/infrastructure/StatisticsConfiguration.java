package com.libra.streaming.recommendation.statistics.infrastructure;

import com.libra.streaming.recommendation.freshness.application.ProjectionFreshness;
import com.libra.streaming.recommendation.statistics.application.PlatformStatistics;
import com.libra.streaming.recommendation.statistics.application.StatisticsStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
public class StatisticsConfiguration {
    @Bean
    StatisticsStore statisticsStore(JdbcTemplate jdbc) { return new JdbcStatisticsStore(jdbc); }

    @Bean
    PlatformStatistics platformStatistics(StatisticsStore store, ProjectionFreshness freshness) {
        return new PlatformStatistics(store, freshness);
    }
}
