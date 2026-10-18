package com.libra.streaming.media.lifecycle.infrastructure;

import com.libra.streaming.media.lifecycle.application.CleanupPlans;
import com.libra.streaming.media.lifecycle.application.CleanupStorage;
import com.libra.streaming.media.lifecycle.application.LifecycleSweeper;
import com.libra.streaming.media.lifecycle.application.ObjectCleanup;
import com.libra.streaming.media.lifecycle.application.UploadExpiry;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "libra.media.lifecycle.enabled", havingValue = "true")
public class LifecycleConfiguration {
    @Bean
    ObjectCleanup objectCleanup(CleanupStorage storage, CleanupPlans plans, LifecycleMetrics metrics,
            @Value("${libra.media.lifecycle.max-objects-per-prefix:1000}") int maxObjectsPerPrefix) {
        return new ObjectCleanup(storage, plans, metrics, maxObjectsPerPrefix);
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    LifecycleSweeper lifecycleSweeper(UploadExpiry expiry, CleanupPlans plans, ObjectCleanup cleanup,
            LifecycleMetrics metrics,
            @Value("${libra.media.lifecycle.poll-interval:1m}") Duration poll,
            @Value("${libra.media.lifecycle.batch-size:20}") int batch,
            @Value("${libra.media.lifecycle.write-grace:1h}") Duration grace,
            @Value("${libra.media.lifecycle.shutdown-timeout:10s}") Duration shutdown) {
        return new LifecycleSweeper(expiry, plans, cleanup, metrics,
                new LifecycleSweeper.Settings(poll, batch, grace, shutdown));
    }
}
