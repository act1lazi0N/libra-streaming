package com.libra.streaming.core.profiles.infrastructure;

import com.libra.streaming.core.events.application.EventPort;
import com.libra.streaming.core.profiles.application.ProfileStore;
import com.libra.streaming.core.profiles.application.ProfileUseCase;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ProfileConfiguration {
    @Bean
    ProfileUseCase profileUseCase(ProfileStore store, EventPort events) {
        return new ProfileUseCase(store, events);
    }
}
