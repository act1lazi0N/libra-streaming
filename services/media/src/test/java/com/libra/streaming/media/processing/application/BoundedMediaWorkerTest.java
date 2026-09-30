package com.libra.streaming.media.processing.application;

import com.libra.streaming.media.processing.domain.JobLease;
import com.libra.streaming.media.processing.domain.ProcessingFailure;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class BoundedMediaWorkerTest {
    @Test
    void oneSlotRenewsWhileHandlerBlocksAndShutdownReleasesLease() throws Exception {
        var leases = mock(JobLeases.class);
        var owner = lease();
        when(leases.claim(any())).thenReturn(Optional.of(owner), Optional.empty());
        when(leases.renew(any(), any())).thenReturn(true);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch renewed = new CountDownLatch(1);
        when(leases.renew(any(), any())).thenAnswer(call -> { renewed.countDown(); return true; });
        var calls = new AtomicInteger();
        try (var worker = worker(leases, (job, cancelled) -> {
            calls.incrementAndGet();
            entered.countDown();
            new CountDownLatch(1).await();
            throw new AssertionError("unexpected handler continuation");
        })) {
            worker.start();
            worker.start();
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(renewed.await(3, TimeUnit.SECONDS)).isTrue();
            verify(leases, times(1)).claim(any());
            assertThat(calls).hasValue(1);
        }
        verify(leases).release(owner);
        verify(leases, never()).retry(any(), any(), any());
        verify(leases, never()).fail(any(), any());
    }

    @Test
    void lostLeaseInterruptsHandlerAndCannotWriteFailureOrReleaseSuccessor() throws Exception {
        var leases = mock(JobLeases.class);
        var owner = lease();
        when(leases.claim(any())).thenReturn(Optional.of(owner), Optional.empty());
        when(leases.renew(any(), any())).thenReturn(false);
        CountDownLatch cancelled = new CountDownLatch(1);
        try (var worker = worker(leases, (job, stop) -> {
            try { new CountDownLatch(1).await(); }
            catch (InterruptedException exception) {
                assertThat(stop.getAsBoolean()).isTrue();
                cancelled.countDown();
                throw exception;
            }
            throw new AssertionError();
        })) {
            worker.start();
            assertThat(cancelled.await(3, TimeUnit.SECONDS)).isTrue();
        }
        verify(leases, never()).release(any());
        verify(leases, never()).retry(any(), any(), any());
        verify(leases, never()).fail(any(), any());
    }

    @Test
    void permanentAndUnexpectedFailuresUseOnlySanitizedClassification() throws Exception {
        for (boolean permanent : new boolean[] {true, false}) {
            var leases = mock(JobLeases.class);
            var owner = lease();
            when(leases.claim(any())).thenReturn(Optional.of(owner), Optional.empty());
            when(leases.renew(any(), any())).thenReturn(true);
            CountDownLatch recorded = new CountDownLatch(1);
            doAnswer(call -> { recorded.countDown(); return true; }).when(leases).fail(any(), any());
            doAnswer(call -> { recorded.countDown(); return true; }).when(leases).retry(any(), any(), any());
            try (var worker = worker(leases, (job, stop) -> {
                if (permanent) { return MediaJobHandler.Outcome.reject(ProcessingFailure.CORRUPT_INPUT); }
                throw new IllegalStateException("sensitive remote diagnostic fixture");
            })) {
                worker.start();
                assertThat(recorded.await(3, TimeUnit.SECONDS)).isTrue();
            }
            if (permanent) { verify(leases).fail(owner, ProcessingFailure.CORRUPT_INPUT); }
            else { verify(leases).retry(owner, ProcessingFailure.PROCESSING_FAILED, Duration.ofMillis(20)); }
        }
    }

    @Test
    void invalidTimersAndRestartAfterCloseAreRejected() {
        assertThatThrownBy(() -> new BoundedMediaWorker.Settings(Duration.ZERO, Duration.ofSeconds(1),
                Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        var worker = worker(mock(JobLeases.class), (job, stop) -> MediaJobHandler.Outcome.retry(ProcessingFailure.PROCESSING_FAILED));
        worker.close();
        assertThatThrownBy(worker::start).isInstanceOf(IllegalStateException.class);
    }

    private static BoundedMediaWorker worker(JobLeases leases, MediaJobHandler handler) {
        return new BoundedMediaWorker(leases, handler, new BoundedMediaWorker.Settings(Duration.ofMillis(20),
                Duration.ofSeconds(1), Duration.ofMillis(20), Duration.ofMillis(20), Duration.ofSeconds(1)), ignored -> {});
    }
    private static JobLease lease() {
        return new JobLease(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, 1,
                UUID.randomUUID(), Instant.now().plusSeconds(1));
    }
}
