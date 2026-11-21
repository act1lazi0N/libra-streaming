package com.libra.streaming.recommendation.preferences.infrastructure;

import com.libra.streaming.recommendation.preferences.application.ProfilePreferences;
import com.libra.streaming.recommendation.preferences.domain.InterestRules;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
public class PreferencesConfiguration {
    /** One instance serves both ports: the watched-time rollup and its erasure with the profile's tombstone. */
    @Bean
    JdbcPreferenceStore preferenceStore(JdbcTemplate jdbc) { return new JdbcPreferenceStore(jdbc); }

    @Bean
    ProfilePreferences profilePreferences(JdbcPreferenceStore store, Clock clock) {
        return new ProfilePreferences(store, InterestRules.DEFAULT, clock);
    }
}
