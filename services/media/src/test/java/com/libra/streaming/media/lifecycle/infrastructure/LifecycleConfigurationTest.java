package com.libra.streaming.media.lifecycle.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.libra.streaming.media.lifecycle.application.CleanupPlans;
import com.libra.streaming.media.lifecycle.application.CleanupStorage;
import com.libra.streaming.media.lifecycle.application.UploadExpiry;
import com.libra.streaming.media.storage.MediaStorageLayout;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class LifecycleConfigurationTest {
    @Test
    void theSweeperNeverStartsBeforeTheStorageLayoutIsChecked() throws Exception {
        // M32: the sweeper derives every key from the layout, so it resolves the check itself rather than rely on
        // bean order. A lazy, failing check models the worst order.
        var expiry = mock(UploadExpiry.class);
        var plans = mock(CleanupPlans.class);
        new ApplicationContextRunner()
                .withInitializer(context -> context.getBeanFactory().setConversionService(
                        org.springframework.boot.convert.ApplicationConversionService.getSharedInstance()))
                .withUserConfiguration(LifecycleConfiguration.class)
                .withPropertyValues("libra.media.lifecycle.enabled=true", "libra.media.lifecycle.poll-interval=1s")
                .withBean(UploadExpiry.class, () -> expiry)
                .withBean(CleanupPlans.class, () -> plans)
                .withBean(CleanupStorage.class, () -> mock(CleanupStorage.class))
                .withBean(LifecycleMetrics.class, () -> mock(LifecycleMetrics.class))
                .withBean(MediaStorageLayout.class, () -> { throw new IllegalStateException("layout differs"); },
                        definition -> definition.setLazyInit(true))
                .run(context -> assertThat(context).hasFailed());
        Thread.sleep(1500);
        verify(expiry, never()).expire(anyInt());
        verify(plans, never()).due(any(), anyInt());
    }
}
