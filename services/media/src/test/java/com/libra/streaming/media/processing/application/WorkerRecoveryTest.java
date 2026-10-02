package com.libra.streaming.media.processing.application;

import com.libra.streaming.media.processing.domain.JobLease;
import com.libra.streaming.media.processing.domain.ProcessingFailure;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class WorkerRecoveryTest {
    @Test
    void databaseLossCancelsLateOutcomeAndDoesNotSpendAnotherAttempt() throws Exception {
        var leases = mock(JobLeases.class);
        var owner = lease();
        when(leases.claim(any())).thenReturn(Optional.of(owner), Optional.empty());
        var entered = new CountDownLatch(1);
        var cancelled = new CountDownLatch(1);
        var finished = new CountDownLatch(1);
        when(leases.renew(any(), any())).thenAnswer(call -> {
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            throw new IllegalStateException("private database diagnostic fixture");
        });
        var reports = new CopyOnWriteArrayList<String>();
        try (var worker = worker(leases, (job, stop) -> {
            entered.countDown();
            try { new CountDownLatch(1).await(); }
            catch (InterruptedException exception) {
                assertThat(stop.getAsBoolean()).isTrue();
                cancelled.countDown();
            }
            // An uncooperative handler still returns a terminal result after cancellation.
            finished.countDown();
            return MediaJobHandler.Outcome.reject(ProcessingFailure.CORRUPT_INPUT);
        }, reports)) {
            worker.start();
            assertThat(cancelled.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(finished.await(3, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(reports).containsExactly("WORKER_LEASE_UNAVAILABLE");
        verify(leases, never()).release(any());
        verify(leases, never()).retry(any(), any(), any());
        verify(leases, never()).fail(any(), any());
    }

    @Test
    void shutdownIsBoundedForHandlerIgnoringInterruptAndLateResultIsCancellation() throws Exception {
        var leases = mock(JobLeases.class);
        var owner = lease();
        when(leases.claim(any())).thenReturn(Optional.of(owner), Optional.empty());
        when(leases.renew(any(), any())).thenReturn(true);
        var entered = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        var released = new CountDownLatch(1);
        when(leases.release(owner)).thenAnswer(call -> { released.countDown(); return true; });
        var reports = new CopyOnWriteArrayList<String>();
        var worker = worker(leases, (job, stop) -> {
            entered.countDown();
            boolean resumed = false;
            while (!resumed) {
                try { resumed = resume.await(3, TimeUnit.SECONDS); }
                catch (InterruptedException ignored) { /* Deliberately ignore shutdown. */ }
            }
            assertThat(stop.getAsBoolean()).isTrue();
            return MediaJobHandler.Outcome.retry(ProcessingFailure.PROCESSING_FAILED);
        }, reports);
        try {
            worker.start();
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            long started = System.nanoTime();
            worker.close();
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
            assertThat(reports).containsExactly("WORKER_SHUTDOWN_TIMEOUT");
            int renewals = mockingDetails(leases).getInvocations().stream()
                    .filter(call -> call.getMethod().getName().equals("renew")).toList().size();
            resume.countDown();
            assertThat(released.await(3, TimeUnit.SECONDS)).isTrue();
            verify(leases, times(renewals)).renew(any(), any());
            verify(leases, times(1)).claim(any());
            verify(leases, never()).retry(any(), any(), any());
            verify(leases, never()).fail(any(), any());
        } finally {
            resume.countDown();
            worker.close();
        }
    }

    @Test
    void delayedHeartbeatCannotInterruptTheFollowingJob() throws Exception {
        var leases = mock(JobLeases.class);
        var first = lease();
        var second = lease();
        when(leases.claim(any())).thenReturn(Optional.of(first), Optional.of(second), Optional.empty());
        var renewing = new CountDownLatch(1);
        var allowRenewal = new CountDownLatch(1);
        var outcomeReturned = new CountDownLatch(1);
        var secondEntered = new CountDownLatch(1);
        when(leases.renew(eq(first), any())).thenAnswer(call -> {
            renewing.countDown();
            assertThat(allowRenewal.await(3, TimeUnit.SECONDS)).isTrue();
            return true;
        });
        when(leases.renew(eq(second), any())).thenReturn(true);
        var reports = new CopyOnWriteArrayList<String>();
        try (var worker = worker(leases, (job, stop) -> {
            if (job.equals(first)) {
                assertThat(renewing.await(3, TimeUnit.SECONDS)).isTrue();
                outcomeReturned.countDown();
                return MediaJobHandler.Outcome.retry(ProcessingFailure.PROCESSING_FAILED);
            }
            assertThat(Thread.currentThread().isInterrupted()).isFalse();
            assertThat(stop.getAsBoolean()).isFalse();
            secondEntered.countDown();
            new CountDownLatch(1).await();
            throw new AssertionError();
        }, reports)) {
            worker.start();
            assertThat(outcomeReturned.await(3, TimeUnit.SECONDS)).isTrue();
            verify(leases, never()).retry(any(), any(), any());
            allowRenewal.countDown();
            assertThat(secondEntered.await(3, TimeUnit.SECONDS)).isTrue();
        } finally {
            allowRenewal.countDown();
        }
        verify(leases).retry(first, ProcessingFailure.PROCESSING_FAILED, Duration.ofMillis(20));
        verify(leases).release(second);
        assertThat(reports).isEmpty();
    }

    private static BoundedMediaWorker worker(JobLeases leases, MediaJobHandler handler,
            CopyOnWriteArrayList<String> reports) {
        return new BoundedMediaWorker(leases, handler, new BoundedMediaWorker.Settings(Duration.ofMillis(20),
                Duration.ofSeconds(1), Duration.ofMillis(20), Duration.ofMillis(20), Duration.ofMillis(100)), reports::add);
    }

    private static JobLease lease() {
        return new JobLease(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, 1,
                UUID.randomUUID(), Instant.now().plusSeconds(1));
    }
}
