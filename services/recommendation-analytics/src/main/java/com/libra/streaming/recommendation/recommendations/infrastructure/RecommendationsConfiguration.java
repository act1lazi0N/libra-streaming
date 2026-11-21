package com.libra.streaming.recommendation.recommendations.infrastructure;

import com.libra.streaming.recommendation.freshness.application.ProjectionFreshness;
import com.libra.streaming.recommendation.preferences.application.ProfilePreferences;
import com.libra.streaming.recommendation.recommendations.application.CandidateStore;
import com.libra.streaming.recommendation.recommendations.application.ProfileRecommendations;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
public class RecommendationsConfiguration {
    @Bean
    CandidateStore candidateStore(JdbcTemplate jdbc) { return new JdbcCandidateStore(jdbc); }

    @Bean
    ProfileRecommendations profileRecommendations(CandidateStore candidates, ProfilePreferences preferences,
            ProjectionFreshness freshness, Clock clock) {
        return new ProfileRecommendations(candidates, preferences, freshness, clock);
    }
}
