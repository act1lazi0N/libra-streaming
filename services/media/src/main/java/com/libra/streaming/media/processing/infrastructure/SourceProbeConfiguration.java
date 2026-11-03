package com.libra.streaming.media.processing.infrastructure;

import com.libra.streaming.media.processing.application.JobSources;
import com.libra.streaming.media.processing.application.MediaProber;
import com.libra.streaming.media.processing.application.SourceFreezer;
import com.libra.streaming.media.processing.application.SourceMetadataStore;
import com.libra.streaming.media.processing.application.SourceProber;
import com.libra.streaming.media.processing.application.SourceReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The probe stage exists only when a trusted ffprobe path is configured; M19 installs no production job handler,
 * so this makes the stage available to one without enabling anything. A configured path that is not an executable
 * file fails startup rather than failing every job later.
 */
@Configuration
@ConditionalOnExpression("!'${libra.media.probe.executable:}'.isEmpty()")
class SourceProbeConfiguration {
    @Bean
    MediaProber mediaProber(@Value("${libra.media.probe.executable}") Path executable,
            @Value("${libra.media.probe.timeout:20s}") Duration timeout,
            @Value("${libra.media.probe.max-output-bytes:262144}") int maxOutputBytes) throws InterruptedException {
        if (!executable.isAbsolute() || !Files.isRegularFile(executable) || !Files.isExecutable(executable)) {
            throw new IllegalStateException("libra.media.probe.executable must be an absolute executable file");
        }
        var prober = new FfprobeMediaProber(executable, timeout, maxOutputBytes);
        LoggerFactory.getLogger(SourceProbeConfiguration.class).info("Media probe: {}", prober.version());
        return prober;
    }

    @Bean
    SourceProber sourceProber(SourceFreezer freezer, JobSources sources, SourceReader reader, MediaProber prober,
            SourceMetadataStore metadata) {
        return new SourceProber(freezer, sources, reader, prober, metadata);
    }
}
