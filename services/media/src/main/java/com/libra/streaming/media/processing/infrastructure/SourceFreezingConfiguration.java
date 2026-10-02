package com.libra.streaming.media.processing.infrastructure;

import com.libra.streaming.media.processing.application.JobSources;
import com.libra.streaming.media.processing.application.SourceFreezer;
import com.libra.streaming.media.processing.application.SourceStorage;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The stage is available to a handler; M17 deliberately does not install a production handler. */
@Configuration
class SourceFreezingConfiguration {
    @Bean
    SourceFreezer sourceFreezer(JobSources sources, SourceStorage storage) {
        return new SourceFreezer(sources, storage);
    }
}
