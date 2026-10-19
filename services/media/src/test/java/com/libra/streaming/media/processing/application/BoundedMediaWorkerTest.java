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
    void aStalledHeartbeatCannotKeepAnExpiredLeaseWriting() throws Exception {
        // M32: the heartbeat is stuck in a renewal (a slow pool or database) or was paused with the process, so it
        // never reports the loss. Once the lease duration has passed since the last confirmed claim or renewal,
        // another worker may own the job, so the handler must stop before its next storage write.
        var leases = mock(JobLeases.class);
        var owner = lease();
        when(leases.claim(any())).thenReturn(Optional.of(owner), Optional.empty());
        CountDownLatch stuck = new CountDownLatch(1);
        when(leases.renew(any(), any())).thenAnswer(call -> { stuck.await(); return true; });
        var beforeExpiry = new java.util.concurrent.atomic.AtomicBoolean(true);
        var afterExpiry = new java.util.concurrent.atomic.AtomicBoolean(false);
        CountDownLatch checked = new CountDownLatch(1);
        var settings = new BoundedMediaWorker.Settings(Duration.ofMillis(20), Duration.ofMillis(300),
                Duration.ofMillis(100), Duration.ofMillis(20), Duration.ofSeconds(1));
        try (var worker = new BoundedMediaWorker(leases, (job, cancelled) -> {
            beforeExpiry.set(cancelled.getAsBoolean());
            Thread.sleep(600);
            afterExpiry.set(cancelled.getAsBoolean());
            checked.countDown();
            return MediaJobHandler.Outcome.retry(ProcessingFailure.PROCESSING_FAILED);
        }, settings, ignored -> {})) {
            worker.start();
            assertThat(checked.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(beforeExpiry).as("a fresh lease is not cancelled").isFalse();
            assertThat(afterExpiry).as("an unconfirmed lease past its duration is cancelled").isTrue();
            // Whatever the handler returns, an expired lease records nothing and releases nothing.
            Thread.sleep(100);
            verify(leases, never()).retry(any(), any(), any());
            verify(leases, never()).fail(any(), any());
            verify(leases, never()).release(any());
        } finally {
            stuck.countDown();
        }
    }

    @Test
    void aLateRenewalCannotReviveALapsedLeaseAtFinalization() throws Exception {
        // The handler never asks; it simply returns after the lease duration. The heartbeat is stuck in a renewal
        // that the database confirms only afterwards, measured from when it was asked: the lease has lapsed, so the
        // outcome is not recorded and nothing is released, whatever the late confirmation says.
        var leases = mock(JobLeases.class);
        var owner = lease();
        when(leases.claim(any())).thenReturn(Optional.of(owner), Optional.empty());
        CountDownLatch stuck = new CountDownLatch(1);
        CountDownLatch returned = new CountDownLatch(1);
        when(leases.renew(any(), any())).thenAnswer(call -> { stuck.await(); return true; })
                .thenReturn(true);
        var settings = new BoundedMediaWorker.Settings(Duration.ofMillis(20), Duration.ofMillis(300),
                Duration.ofMillis(100), Duration.ofMillis(20), Duration.ofSeconds(1));
        try (var worker = new BoundedMediaWorker(leases, (job, cancelled) -> {
            Thread.sleep(600);
            returned.countDown();
            return MediaJobHandler.Outcome.retry(ProcessingFailure.PROCESSING_FAILED);
        }, settings, ignored -> {})) {
            worker.start();
            assertThat(returned.await(3, TimeUnit.SECONDS)).isTrue();
            stuck.countDown();
            verify(leases, timeout(1000).atLeast(2)).claim(any());
            verify(leases, never()).retry(any(), any(), any());
            verify(leases, never()).fail(any(), any());
            verify(leases, never()).release(any());
            verify(leases, times(1)).renew(any(), any());
        } finally {
            stuck.countDown();
        }
    }

    @Test
    void everyConfirmedRenewalExtendsWhatTheHandlerMayStillDo() throws Exception {
        var leases = mock(JobLeases.class);
        var owner = lease();
        when(leases.claim(any())).thenReturn(Optional.of(owner), Optional.empty());
        when(leases.renew(any(), any())).thenReturn(true);
        var cancelledAt = new AtomicInteger(-1);
        CountDownLatch done = new CountDownLatch(1);
        var settings = new BoundedMediaWorker.Settings(Duration.ofMillis(20), Duration.ofMillis(300),
                Duration.ofMillis(50), Duration.ofMillis(20), Duration.ofSeconds(1));
        try (var worker = new BoundedMediaWorker(leases, (job, cancelled) -> {
            // Three lease durations with a healthy heartbeat: never cancelled.
            for (int check = 0; check < 18; check++) {
                if (cancelled.getAsBoolean()) { cancelledAt.compareAndSet(-1, check); }
                Thread.sleep(50);
            }
            done.countDown();
            return MediaJobHandler.Outcome.settled();
        }, settings, ignored -> {})) {
            worker.start();
            assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(cancelledAt).hasValue(-1);
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

    @Test
    void aSettledOutcomeRecordsNothingAndReleasesNothing() throws Exception {
        var leases = mock(JobLeases.class);
        var owner = lease();
        when(leases.claim(any())).thenReturn(Optional.of(owner), Optional.empty());
        when(leases.renew(any(), any())).thenReturn(true);
        CountDownLatch handled = new CountDownLatch(1);
        try (var worker = worker(leases, (job, stop) -> {
            handled.countDown();
            return MediaJobHandler.Outcome.settled();
        })) {
            worker.start();
            assertThat(handled.await(3, TimeUnit.SECONDS)).isTrue();
            verify(leases, timeout(1000).atLeast(2)).claim(any());
        }
        // READY was committed by the handler under its lease; a worker write could only be refused or wrong.
        verify(leases, never()).retry(any(), any(), any());
        verify(leases, never()).fail(any(), any());
        verify(leases, never()).release(any());
        assertThat(MediaJobHandler.Outcome.settled().isSettled()).isTrue();
        assertThat(MediaJobHandler.Outcome.retry(ProcessingFailure.PROCESSING_FAILED).isSettled()).isFalse();
        assertThatThrownBy(() -> new MediaJobHandler.Outcome(true, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MediaJobHandler.Outcome.reject(null)).isInstanceOf(NullPointerException.class);
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
