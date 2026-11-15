package com.libra.streaming.recommendation;

import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class RecommendationAnalyticsApplication {
    @Bean
    Clock clock() { return Clock.systemUTC(); }

    public static void main(String[] args) {
        SpringApplication.run(RecommendationAnalyticsApplication.class, args);
    }
}

