package com.libra.streaming.media;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import java.time.Clock;

@SpringBootApplication
public class MediaApplication {
    @Bean
    Clock clock() { return Clock.systemUTC(); }

    public static void main(String[] args) {
        SpringApplication.run(MediaApplication.class, args);
    }
}

