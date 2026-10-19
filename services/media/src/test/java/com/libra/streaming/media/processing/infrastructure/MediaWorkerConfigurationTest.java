package com.libra.streaming.media.processing.infrastructure;

import com.libra.streaming.media.processing.application.BoundedMediaWorker;
import com.libra.streaming.media.processing.application.JobLeases;
import com.libra.streaming.media.processing.application.MediaJobHandler;
import com.libra.streaming.media.processing.domain.ProcessingFailure;
import com.libra.streaming.media.storage.MediaStorageLayout;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class MediaWorkerConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(context -> context.getBeanFactory().setConversionService(
                    org.springframework.boot.convert.ApplicationConversionService.getSharedInstance()))
            .withUserConfiguration(MediaWorkerConfiguration.class);

    @Test
    void workerIsAbsentByDefaultAndEnablingWithoutRealHandlerFailsClosed() {
        runner.run(context -> assertThat(context).doesNotHaveBean(BoundedMediaWorker.class));
        runner.withPropertyValues("libra.media.worker.enabled=true")
                .withBean(JobLeases.class, () -> mock(JobLeases.class))
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void theWorkerNeverStartsBeforeTheStorageLayoutIsChecked() throws Exception {
        // M32: nothing else is guaranteed to create the layout check first (the storage adapters reach S3 lazily),
        // so the worker resolves it itself. A lazy, failing check models the worst bean order.
        var leases = mock(JobLeases.class);
        when(leases.claim(any())).thenReturn(Optional.empty());
        runner.withPropertyValues("libra.media.worker.enabled=true", "libra.media.worker.poll-interval=10ms")
                .withBean(JobLeases.class, () -> leases)
                .withBean(MediaJobHandler.class, () -> (job, cancelled) -> MediaJobHandler.Outcome.settled())
                .withBean(MediaStorageLayout.class, () -> { throw new IllegalStateException("layout differs"); },
                        definition -> definition.setLazyInit(true))
                .run(context -> assertThat(context).hasFailed());
        Thread.sleep(100);
        verify(leases, never()).claim(any());
    }

    @Test
    void controlledHandlerWiresAndInvalidRenewalTimingRejectsStartup() {
        var leases = mock(JobLeases.class);
        when(leases.claim(any())).thenReturn(Optional.empty());
        var enabled = runner.withPropertyValues("libra.media.worker.enabled=true")
                .withBean(JobLeases.class, () -> leases)
                .withBean(MediaJobHandler.class, () -> (job, cancelled) ->
                        MediaJobHandler.Outcome.retry(ProcessingFailure.PROCESSING_FAILED));
        enabled.run(context -> assertThat(context).hasSingleBean(BoundedMediaWorker.class));
        enabled.withPropertyValues("libra.media.worker.lease-duration=1s", "libra.media.worker.renew-interval=1s")
                .run(context -> assertThat(context).hasFailed());
    }
}
