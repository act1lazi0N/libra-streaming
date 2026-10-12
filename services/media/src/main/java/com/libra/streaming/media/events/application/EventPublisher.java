package com.libra.streaming.media.events.application;

import com.libra.streaming.media.events.domain.OutboxDelivery;
import java.time.Duration;

/** The broker. A normal return means the broker acknowledged the record; any runtime exception means it did not. */
public interface EventPublisher {
    void publish(OutboxDelivery delivery, Duration timeout) throws InterruptedException;
}
