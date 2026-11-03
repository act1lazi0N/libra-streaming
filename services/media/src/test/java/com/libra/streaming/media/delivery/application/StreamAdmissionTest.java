package com.libra.streaming.media.delivery.application;

import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** Admission alone: both limits, refusals that hold nothing, and exact release under contention. */
class StreamAdmissionTest {
    final UUID session = UUID.randomUUID();

    @Test
    void aSessionHoldsAtMostItsShareAndAReleasedPermitIsReusable() {
        var admission = new StreamAdmission(8, 2);
        var first = admission.admit(session);
        var second = admission.admit(session);
        assertThatThrownBy(() -> admission.admit(session)).isInstanceOf(StreamAdmission.SessionBusy.class);
        // Another session is unaffected, and the refusal held nothing.
        admission.admit(UUID.randomUUID()).close();
        assertThat(admission.held(session)).isEqualTo(2);
        assertThat(admission.available()).isEqualTo(6);

        first.close();
        first.close(); // a second close releases nothing more
        assertThat(admission.held(session)).isEqualTo(1);
        assertThat(admission.available()).isEqualTo(7);
        admission.admit(session).close();
        second.close();
        assertThat(admission.held(session)).isZero();
        assertThat(admission.available()).isEqualTo(8);
    }

    @Test
    void theInstanceLimitRefusesEverySessionAndTheRefusalReleasesTheSessionsShare() {
        var admission = new StreamAdmission(2, 2);
        var a = admission.admit(UUID.randomUUID());
        var b = admission.admit(session);
        assertThatThrownBy(() -> admission.admit(UUID.randomUUID())).isInstanceOf(StreamAdmission.Busy.class);
        assertThatThrownBy(() -> admission.admit(session)).isInstanceOf(StreamAdmission.Busy.class);
        assertThat(admission.held(session)).as("a refused request holds no session share").isEqualTo(1);
        a.close();
        admission.admit(session).close();
        b.close();
        assertThat(admission.available()).isEqualTo(2);
        assertThat(admission.held(session)).isZero();
    }

    @Test
    void limitsMustBeConsistent() {
        assertThatThrownBy(() -> new StreamAdmission(0, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StreamAdmission(4, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StreamAdmission(4, 5)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void underContentionNoLimitIsExceededAndEveryPermitComesBack() throws Exception {
        var admission = new StreamAdmission(6, 3);
        var sessions = new UUID[]{session, UUID.randomUUID(), UUID.randomUUID()};
        var perSession = new AtomicInteger[]{new AtomicInteger(), new AtomicInteger(), new AtomicInteger()};
        var total = new AtomicInteger();
        var peakSession = new AtomicInteger();
        var peakTotal = new AtomicInteger();
        var admitted = new AtomicInteger();
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(24);
        var runs = new ArrayList<Future<?>>();
        try {
            for (int t = 0; t < 24; t++) {
                int s = t % 3;
                runs.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < 2_000; i++) {
                        try (var permit = admission.admit(sessions[s])) {
                            admitted.incrementAndGet();
                            peakSession.accumulateAndGet(perSession[s].incrementAndGet(), Math::max);
                            peakTotal.accumulateAndGet(total.incrementAndGet(), Math::max);
                            Thread.onSpinWait();
                            total.decrementAndGet();
                            perSession[s].decrementAndGet();
                        } catch (StreamAdmission.SessionBusy | StreamAdmission.Busy refused) {
                            // expected under contention
                        }
                    }
                    return null;
                }));
            }
            start.countDown();
            for (var run : runs) { run.get(60, TimeUnit.SECONDS); }
        } finally { pool.shutdownNow(); }
        assertThat(admitted.get()).isPositive();
        assertThat(peakSession.get()).isBetween(1, 3);
        assertThat(peakTotal.get()).isBetween(1, 6);
        assertThat(admission.available()).isEqualTo(6);
        for (var id : sessions) { assertThat(admission.held(id)).isZero(); }
    }
}
