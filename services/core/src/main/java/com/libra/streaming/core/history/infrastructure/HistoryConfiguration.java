package com.libra.streaming.core.history.infrastructure;

import com.libra.streaming.core.history.application.HistoryStore;
import com.libra.streaming.core.history.application.HistoryUseCase;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class HistoryConfiguration {
    @Bean
    HistoryUseCase historyUseCase(HistoryStore store, Clock clock) {
        return new HistoryUseCase(store, clock);
    }
}
