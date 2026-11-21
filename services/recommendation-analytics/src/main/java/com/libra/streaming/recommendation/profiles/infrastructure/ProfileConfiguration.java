package com.libra.streaming.recommendation.profiles.infrastructure;

import com.libra.streaming.recommendation.profiles.application.ProfileErasure;
import com.libra.streaming.recommendation.profiles.application.ProfileProjection;
import com.libra.streaming.recommendation.profiles.application.ProfileStore;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
public class ProfileConfiguration {
    @Bean
    ProfileStore profileStore(JdbcTemplate jdbc) { return new JdbcProfileStore(jdbc); }

    @Bean
    ProfileProjection profileProjection(ProfileStore store, List<ProfileErasure> erasures) {
        return new ProfileProjection(store, erasures);
    }
}
