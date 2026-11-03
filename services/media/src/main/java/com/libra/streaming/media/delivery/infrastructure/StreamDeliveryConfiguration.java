package com.libra.streaming.media.delivery.infrastructure;

import com.libra.streaming.media.delivery.application.DeliverableAssets;
import com.libra.streaming.media.delivery.application.StreamDelivery;
import com.libra.streaming.media.delivery.application.StreamObjects;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(StreamDeliveryProperties.class)
public class StreamDeliveryConfiguration {
    @Bean
    StreamDelivery streamDelivery(DeliverableAssets assets, StreamObjects objects, StreamDeliveryProperties properties,
            Clock clock) {
        return new StreamDelivery(assets, objects, properties.limits(), clock);
    }
}
