package com.libra.streaming.media.events.application;

import com.libra.streaming.media.events.domain.DeliveryFailure;
import com.libra.streaming.media.events.domain.OutboxDelivery;
import java.time.Duration;
import java.util.Objects;

/** The broker. A normal return means the broker acknowledged the record; any runtime exception means it did not. */
public interface EventPublisher {
    void publish(OutboxDelivery delivery, Duration timeout) throws InterruptedException;

    /** A classified non-acknowledgement. Other runtime exceptions count as {@code BROKER_UNAVAILABLE}. */
    final class Failure extends RuntimeException {
        private final DeliveryFailure reason;

        public Failure(DeliveryFailure reason) {
            super(Objects.requireNonNull(reason).name(), null, false, false);
            this.reason = reason;
        }

        public DeliveryFailure reason() { return reason; }
    }
}
