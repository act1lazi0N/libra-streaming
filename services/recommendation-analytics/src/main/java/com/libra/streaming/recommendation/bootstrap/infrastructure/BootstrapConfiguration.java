package com.libra.streaming.recommendation.bootstrap.infrastructure;

import com.libra.streaming.recommendation.bootstrap.application.Bootstrap;
import com.libra.streaming.recommendation.bootstrap.application.BootstrapBackfill;
import com.libra.streaming.recommendation.freshness.application.BackfillProgress;
import com.libra.streaming.recommendation.bootstrap.application.BootstrapProgress;
import com.libra.streaming.recommendation.bootstrap.application.CoreSnapshots;
import com.libra.streaming.recommendation.catalog.application.CatalogProjection;
import com.libra.streaming.recommendation.ingestion.application.EventIntake;
import com.libra.streaming.recommendation.ingestion.application.Transactions;
import com.libra.streaming.recommendation.internalapi.infrastructure.CoreServiceProperties;
import com.libra.streaming.recommendation.profiles.application.ProfileProjection;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({BootstrapProperties.class, CoreServiceProperties.class})
public class BootstrapConfiguration {
    @Bean
    BootstrapTokens bootstrapTokens(BootstrapProperties properties, CoreServiceProperties core, Clock clock) {
        return new BootstrapTokens(properties, core, clock);
    }

    @Bean
    HttpCoreSnapshots coreSnapshots(BootstrapProperties properties, BootstrapTokens tokens, Clock clock) {
        return new HttpCoreSnapshots(properties, tokens, clock);
    }

    @Bean
    BootstrapProgress bootstrapProgress(JdbcTemplate jdbc) { return new JdbcBootstrapProgress(jdbc); }

    @Bean
    BackfillProgress backfillProgress(BootstrapProgress progress, BootstrapProperties properties) {
        return new BootstrapBackfill(progress, properties.enabled(), properties.generation());
    }

    @Bean
    Bootstrap bootstrap(CoreSnapshots snapshots, BootstrapProgress progress, Transactions transactions,
            ProfileProjection profiles, CatalogProjection catalog, EventIntake intake, Clock clock) {
        return new Bootstrap(snapshots, progress, transactions, profiles, catalog, intake, clock);
    }

    @Bean
    @ConditionalOnProperty(prefix = "libra.analytics.bootstrap", name = "enabled", havingValue = "true")
    BootstrapRunner bootstrapRunner(Bootstrap bootstrap, BootstrapProperties properties, MeterRegistry registry) {
        return new BootstrapRunner(bootstrap, properties, registry);
    }
}
