package com.libra.streaming.recommendation.playback.infrastructure;

import com.libra.streaming.recommendation.playback.application.PlaybackProjection;
import com.libra.streaming.recommendation.preferences.application.ProfilePreferences;
import com.libra.streaming.recommendation.statistics.application.PlatformStatistics;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
public class PlaybackConfiguration {
    /** One instance serves both ports: the fact store and the profile erasure of playback links. */
    @Bean
    JdbcPlaybackStore playbackStore(JdbcTemplate jdbc) { return new JdbcPlaybackStore(jdbc); }

    @Bean
    PlaybackProjection playbackProjection(JdbcPlaybackStore store, PlatformStatistics statistics,
            ProfilePreferences preferences) {
        return new PlaybackProjection(store, statistics, preferences);
    }
}
