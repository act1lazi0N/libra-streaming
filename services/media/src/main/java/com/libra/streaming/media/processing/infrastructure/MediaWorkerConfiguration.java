package com.libra.streaming.media.processing.infrastructure;

import com.libra.streaming.media.processing.application.BoundedMediaWorker;
import com.libra.streaming.media.processing.application.JobLeases;
import com.libra.streaming.media.processing.application.MediaJobHandler;
import java.time.Duration;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "libra.media.worker.enabled", havingValue = "true")
public class MediaWorkerConfiguration {
    @Bean(initMethod = "start", destroyMethod = "close")
    BoundedMediaWorker mediaWorker(JobLeases leases, MediaJobHandler handler,
            @Value("${libra.media.worker.poll-interval:1s}") Duration poll,
            @Value("${libra.media.worker.lease-duration:30s}") Duration lease,
            @Value("${libra.media.worker.renew-interval:5s}") Duration renew,
            @Value("${libra.media.worker.retry-delay:10s}") Duration retry,
            @Value("${libra.media.worker.shutdown-timeout:10s}") Duration shutdown) {
        return new BoundedMediaWorker(leases, handler,
                new BoundedMediaWorker.Settings(poll, lease, renew, retry, shutdown),
                code -> LoggerFactory.getLogger(MediaWorkerConfiguration.class).warn("Media worker: {}", code));
    }
}
