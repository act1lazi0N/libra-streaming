package com.libra.streaming.media.events.application;

import com.libra.streaming.media.events.domain.DeliveryFailure;
import com.libra.streaming.media.events.domain.OutboxDelivery;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class OutboxRelayTest {
    private static final OutboxRelay.Settings SETTINGS = new OutboxRelay.Settings(Duration.ofMillis(20),
            Duration.ofSeconds(2), Duration.ofSeconds(1), Duration.ofMinutes(5), Duration.ofSeconds(2));

    private final Events events = new Events();
    private final List<String> reports = new CopyOnWriteArrayList<>();

    @Test
    void publishesEveryDueEventAndRecordsEachAcknowledgement() {
        var first = events.add();
        var second = events.add();
        var published = new ArrayList<OutboxDelivery>();
        relay((delivery, timeout) -> published.add(delivery)).drain();

        assertThat(published).containsExactly(first, second);
        assertThat(events.log).containsExactly("sent " + first.eventId(), "sent " + second.eventId());
        assertThat(reports).containsExactly(OutboxRelay.ACKNOWLEDGED, OutboxRelay.ACKNOWLEDGED);
    }

    @Test
    void aBrokerFailureReturnsTheEventWithBackoffAndStopsTheDrain() {
        var first = events.add();
        events.add();
        relay((delivery, timeout) -> { throw new IllegalStateException("down"); }).drain();

        // Only one claim: the next event would meet the same broker. An unclassified error counts as unavailable.
        assertThat(events.log).containsExactly("retry " + first.eventId() + " BROKER_UNAVAILABLE PT5M");
        assertThat(reports).containsExactly("OUTBOX_BROKER_UNAVAILABLE");
    }

    @Test
    void aRejectionIsRecordedAsSuch() {
        var first = events.add();
        relay((delivery, timeout) -> { throw new EventPublisher.Failure(DeliveryFailure.BROKER_REJECTED); }).drain();
        assertThat(events.log).containsExactly("retry " + first.eventId() + " BROKER_REJECTED PT5M");
        assertThat(reports).containsExactly("OUTBOX_BROKER_REJECTED");
    }

    @Test
    void anInterruptedSendReturnsTheEventAtOnceAndKeepsTheInterrupt() {
        var first = events.add();
        try {
            relay((delivery, timeout) -> { throw new InterruptedException(); }).drain();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
        assertThat(events.log).containsExactly("release " + first.eventId());
        assertThat(reports).isEmpty();
    }

    @Test
    void aFencedAcknowledgementIsReportedAndTheDrainContinues() {
        var first = events.add();
        var second = events.add();
        events.fenced = first.eventId();
        relay((delivery, timeout) -> {}).drain();

        assertThat(events.log).containsExactly("fenced " + first.eventId(), "sent " + second.eventId());
        assertThat(reports).containsExactly("OUTBOX_DELIVERY_FENCED", OutboxRelay.ACKNOWLEDGED);
    }

    @Test
    void databaseFailuresAreReportedWithoutPublishingAnything() {
        events.add();
        events.claimFails = true;
        var published = new ArrayList<OutboxDelivery>();
        relay((delivery, timeout) -> published.add(delivery)).drain();
        assertThat(published).isEmpty();
        assertThat(reports).containsExactly("OUTBOX_DATABASE_UNAVAILABLE");
    }

    @Test
    void anAcknowledgementThatCannotBeRecordedLeavesTheLeaseToExpire() {
        var first = events.add();
        events.add();
        events.ackFails = true;
        var published = new ArrayList<OutboxDelivery>();
        relay((delivery, timeout) -> published.add(delivery)).drain();
        // The record is on the broker; nothing returns the event early, and the drain stops.
        assertThat(published).containsExactly(first);
        assertThat(events.log).isEmpty();
        assertThat(reports).containsExactly("OUTBOX_DATABASE_UNAVAILABLE");
    }

    @Test
    void oneDrainIsBounded() {
        for (int i = 0; i < OutboxRelay.MAX_PER_DRAIN + 5; i++) { events.add(); }
        relay((delivery, timeout) -> {}).drain();
        assertThat(events.log).hasSize(OutboxRelay.MAX_PER_DRAIN);
    }

    @Test
    void theSendTimeoutIsPassedToTheBroker() {
        events.add();
        var timeouts = new ArrayList<Duration>();
        relay((delivery, timeout) -> timeouts.add(timeout)).drain();
        assertThat(timeouts).containsExactly(Duration.ofSeconds(1));
    }

    @Test
    void closeInterruptsASendInProgressAndReturnsTheEvent() throws Exception {
        var first = events.add();
        var sending = new CountDownLatch(1);
        var relay = relay((delivery, timeout) -> {
            sending.countDown();
            Thread.sleep(10_000);
        });
        relay.start();
        assertThat(sending.await(5, TimeUnit.SECONDS)).isTrue();
        relay.close();
        assertThat(events.log).containsExactly("release " + first.eventId());
        assertThatThrownBy(relay::start).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theLeaseMustOutliveASendByAClearMargin() {
        assertThatThrownBy(() -> new OutboxRelay.Settings(Duration.ofSeconds(1), Duration.ofSeconds(15),
                Duration.ofSeconds(10), Duration.ofMinutes(5), Duration.ofSeconds(10)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OutboxRelay.Settings(Duration.ofSeconds(1), Duration.ofSeconds(30),
                null, Duration.ofMinutes(5), Duration.ofSeconds(10))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OutboxRelay.Settings(Duration.ofSeconds(1), Duration.ofSeconds(30),
                Duration.ofSeconds(10), Duration.ZERO, Duration.ofSeconds(10)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new OutboxRelay.Settings(Duration.ofSeconds(1), Duration.ofSeconds(30), Duration.ofSeconds(10),
                Duration.ofMinutes(5), Duration.ofSeconds(10)).leaseDuration()).isEqualTo(Duration.ofSeconds(30));
    }

    private OutboxRelay relay(EventPublisher publisher) {
        return new OutboxRelay(events, publisher, SETTINGS, reports::add);
    }

    /** In-memory delivery state; records each settled outcome in order. */
    private static final class Events implements OutboxEvents {
        final Deque<OutboxDelivery> due = new ArrayDeque<>();
        final List<String> log = new CopyOnWriteArrayList<>();
        volatile UUID fenced;
        volatile boolean claimFails;
        volatile boolean ackFails;

        OutboxDelivery add() {
            var delivery = new OutboxDelivery(UUID.randomUUID(), UUID.randomUUID(), 1, "media.assets.v1",
                    UUID.randomUUID().toString(), "{}");
            due.add(delivery);
            return delivery;
        }

        @Override public synchronized Optional<OutboxDelivery> claim(Duration leaseDuration) {
            if (claimFails) { throw new IllegalStateException("database"); }
            return Optional.ofNullable(due.poll());
        }

        @Override public boolean sent(OutboxDelivery delivery) {
            if (ackFails) { throw new IllegalStateException("database"); }
            if (delivery.eventId().equals(fenced)) { log.add("fenced " + delivery.eventId()); return false; }
            log.add("sent " + delivery.eventId());
            return true;
        }

        @Override public boolean retry(OutboxDelivery delivery, DeliveryFailure failure, Duration maxDelay) {
            log.add("retry " + delivery.eventId() + " " + failure + " " + maxDelay);
            return true;
        }

        @Override public boolean release(OutboxDelivery delivery) {
            log.add("release " + delivery.eventId());
            return true;
        }
    }
}
