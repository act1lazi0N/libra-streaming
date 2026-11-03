package com.libra.streaming.media.processing.infrastructure;

import com.libra.streaming.media.processing.application.JobSources;
import com.libra.streaming.media.processing.application.JobStages;
import com.libra.streaming.media.processing.application.MediaTranscoder;
import com.libra.streaming.media.processing.application.SourceProber;
import com.libra.streaming.media.processing.application.SourceReader;
import com.libra.streaming.media.processing.application.SourceTranscoder;
import com.libra.streaming.media.processing.application.TranscodeWorkspaces;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** The stage is opt-in and fails startup, not jobs, when its executable or its probe is missing. */
class SourceTranscodeConfigurationTest {
    private static final boolean WINDOWS = System.getProperty("os.name").toLowerCase().contains("win");
    @TempDir Path directory;

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(context -> context.getBeanFactory().setConversionService(
                    org.springframework.boot.convert.ApplicationConversionService.getSharedInstance()))
            .withUserConfiguration(SourceTranscodeConfiguration.class)
            .withBean(JobSources.class, () -> mock(JobSources.class))
            .withBean(SourceReader.class, () -> mock(SourceReader.class))
            .withBean(TranscodeWorkspaces.class, () -> mock(TranscodeWorkspaces.class))
            .withBean(JobStages.class, () -> mock(JobStages.class));

    private Path tool() throws IOException {
        var file = directory.resolve(WINDOWS ? "ffmpeg.cmd" : "ffmpeg.sh");
        Files.writeString(file, WINDOWS ? "@echo off\r\necho ffmpeg version 0.0-test\r\n"
                : "#!/bin/sh\necho 'ffmpeg version 0.0-test'\n");
        assertThat(file.toFile().setExecutable(true)).isTrue();
        return file.toAbsolutePath();
    }

    @Test
    void nothingIsWiredUnlessAnExecutableIsConfigured() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(SourceTranscoder.class).doesNotHaveBean(MediaTranscoder.class);
        });
    }

    @Test
    void aConfiguredExecutableWithTheProbeWiresTheStage() throws Exception {
        var executable = tool().toString();
        runner.withBean(SourceProber.class, () -> mock(SourceProber.class))
                .withPropertyValues("libra.media.transcode.executable=" + executable)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(SourceTranscoder.class).hasSingleBean(MediaTranscoder.class);
                });
    }

    @Test
    void aMissingOrRelativeOrNonExecutableToolRejectsStartup() throws Exception {
        var probe = runner.withBean(SourceProber.class, () -> mock(SourceProber.class));
        probe.withPropertyValues("libra.media.transcode.executable=" + directory.resolve("absent").toAbsolutePath())
                .run(context -> assertThat(context).hasFailed());
        probe.withPropertyValues("libra.media.transcode.executable=ffmpeg")
                .run(context -> assertThat(context).hasFailed());
        // A directory is not an executable file.
        probe.withPropertyValues("libra.media.transcode.executable=" + directory.toAbsolutePath())
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void theStageWithoutItsProbeRejectsStartupAndNamesTheMissingSetting() throws Exception {
        runner.withPropertyValues("libra.media.transcode.executable=" + tool())
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasStackTraceContaining("libra.media.probe.executable"));
    }

    @Test
    void unsafeLimitsRejectStartup() throws Exception {
        var configured = runner.withBean(SourceProber.class, () -> mock(SourceProber.class))
                .withPropertyValues("libra.media.transcode.executable=" + tool());
        configured.withPropertyValues("libra.media.transcode.timeout=2h").run(context -> assertThat(context).hasFailed());
        configured.withPropertyValues("libra.media.transcode.threads=0").run(context -> assertThat(context).hasFailed());
        configured.withPropertyValues("libra.media.transcode.max-output-bytes=10")
                .run(context -> assertThat(context).hasFailed());
    }
}
