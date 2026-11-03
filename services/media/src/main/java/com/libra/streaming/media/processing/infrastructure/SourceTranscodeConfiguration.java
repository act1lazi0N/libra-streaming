package com.libra.streaming.media.processing.infrastructure;

import com.libra.streaming.media.processing.application.JobSources;
import com.libra.streaming.media.processing.application.JobStages;
import com.libra.streaming.media.processing.application.MediaTranscoder;
import com.libra.streaming.media.processing.application.SourceProber;
import com.libra.streaming.media.processing.application.SourceReader;
import com.libra.streaming.media.processing.application.SourceTranscoder;
import com.libra.streaming.media.processing.application.TranscodeWorkspaces;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The transcode stage exists only when a trusted ffmpeg path is configured; M21 installs no production job handler,
 * so this makes the stage available to one without enabling anything. A configured path that is not an executable
 * file fails startup rather than failing every job later, and so does a stage without its probe: the encoder never
 * runs on a source that was not judged first.
 */
@Configuration
@ConditionalOnExpression("!'${libra.media.transcode.executable:}'.isEmpty()")
class SourceTranscodeConfiguration {
    @Bean
    MediaTranscoder mediaTranscoder(@Value("${libra.media.transcode.executable}") Path executable,
            @Value("${libra.media.transcode.timeout:15m}") Duration timeout,
            @Value("${libra.media.transcode.threads:2}") int threads,
            @Value("${libra.media.transcode.max-output-bytes:314572800}") long maxOutputBytes)
            throws InterruptedException {
        if (!executable.isAbsolute() || !Files.isRegularFile(executable) || !Files.isExecutable(executable)) {
            throw new IllegalStateException("libra.media.transcode.executable must be an absolute executable file");
        }
        var transcoder = new FfmpegMediaTranscoder(executable, timeout, threads, maxOutputBytes);
        LoggerFactory.getLogger(SourceTranscodeConfiguration.class).info("Media transcode: {}", transcoder.version());
        return transcoder;
    }

    @Bean
    SourceTranscoder sourceTranscoder(ObjectProvider<SourceProber> prober, JobSources sources, SourceReader reader,
            MediaTranscoder transcoder, TranscodeWorkspaces workspaces, JobStages stages,
            @Value("${libra.media.transcode.max-output-bytes:314572800}") long maxOutputBytes) {
        var probe = prober.getIfAvailable();
        if (probe == null) {
            throw new IllegalStateException("libra.media.transcode.executable requires libra.media.probe.executable");
        }
        return new SourceTranscoder(probe, sources, reader, transcoder, workspaces, stages, maxOutputBytes);
    }
}
