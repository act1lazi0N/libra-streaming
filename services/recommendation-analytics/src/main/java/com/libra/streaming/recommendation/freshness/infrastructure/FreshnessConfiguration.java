package com.libra.streaming.recommendation.freshness.infrastructure;

import com.libra.streaming.recommendation.freshness.application.BackfillProgress;
import com.libra.streaming.recommendation.freshness.application.CheckpointAdvancer;
import com.libra.streaming.recommendation.freshness.application.CheckpointStore;
import com.libra.streaming.recommendation.freshness.application.ConsumerProgress;
import com.libra.streaming.recommendation.freshness.application.ProjectionFreshness;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaAdmin;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CheckpointProperties.class)
public class FreshnessConfiguration {
    @Bean
    CheckpointStore checkpointStore(JdbcTemplate jdbc) { return new JdbcCheckpointStore(jdbc); }

    @Bean
    ProjectionFreshness projectionFreshness(CheckpointStore store, Clock clock) {
        return new ProjectionFreshness(store, clock);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "libra.analytics.freshness.checkpoints", name = "enabled", havingValue = "true")
    static class Checkpoints {
        /** A dedicated admin client with short timeouts: a slow broker fails one tick instead of blocking the next. */
        @Bean(destroyMethod = "close")
        Admin checkpointAdmin(KafkaAdmin kafkaAdmin, CheckpointProperties properties) {
            Map<String, Object> config = new HashMap<>(kafkaAdmin.getConfigurationProperties());
            int timeout = Math.toIntExact(properties.adminTimeout().toMillis());
            config.put(AdminClientConfig.CLIENT_ID_CONFIG, "analytics-checkpoints");
            config.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, timeout);
            config.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, timeout);
            return Admin.create(config);
        }

        @Bean
        ConsumerProgress consumerProgress(Admin checkpointAdmin, CheckpointProperties properties) {
            return new KafkaConsumerProgress(checkpointAdmin, properties.adminTimeout());
        }

        @Bean
        CheckpointAdvancer checkpointAdvancer(ConsumerProgress consumers, BackfillProgress backfill,
                ProjectionFreshness freshness, Clock clock) {
            return new CheckpointAdvancer(consumers, backfill, freshness, clock);
        }

        @Bean
        CheckpointScheduler checkpointScheduler(CheckpointAdvancer advancer, CheckpointProperties properties,
                MeterRegistry metrics) {
            return new CheckpointScheduler(advancer, properties.interval(), metrics);
        }
    }
}
