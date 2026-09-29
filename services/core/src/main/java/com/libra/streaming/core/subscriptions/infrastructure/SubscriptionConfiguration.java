package com.libra.streaming.core.subscriptions.infrastructure;

import com.libra.streaming.core.subscriptions.application.SubscriptionStore;
import com.libra.streaming.core.subscriptions.application.SubscriptionUseCase;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SubscriptionConfiguration {
    @Bean
    SubscriptionUseCase subscriptionUseCase(SubscriptionStore store, Clock clock) {
        return new SubscriptionUseCase(store, clock);
    }
}
