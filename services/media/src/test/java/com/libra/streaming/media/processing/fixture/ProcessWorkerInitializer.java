package com.libra.streaming.media.processing.fixture;

import com.libra.streaming.media.processing.application.MediaJobHandler;
import com.libra.streaming.media.processing.domain.ProcessingFailure;
import java.util.concurrent.CountDownLatch;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

/** Loaded only from a test fixture JAR by the packaged-process recovery test. */
public final class ProcessWorkerInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {
    @Override
    public void initialize(ConfigurableApplicationContext context) {
        context.getBeanFactory().registerSingleton("processRecoveryHandler", (MediaJobHandler) (lease, cancelled) -> {
            System.out.println("M16_CLAIM " + lease.jobId() + " " + lease.attempt());
            if (context.getEnvironment().getProperty("m16.fixture.block", Boolean.class, true)) {
                new CountDownLatch(1).await();
            }
            return MediaJobHandler.Outcome.retry(ProcessingFailure.PROCESSING_FAILED);
        });
    }
}
