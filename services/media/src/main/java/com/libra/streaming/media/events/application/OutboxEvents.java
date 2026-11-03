package com.libra.streaming.media.events.application;

import com.libra.streaming.media.events.domain.DeliveryFailure;
import com.libra.streaming.media.events.domain.OutboxDelivery;
import java.time.Duration;
import java.util.Optional;

/**
 * Delivery state of the Media outbox ({@code PENDING -> LEASED -> SENT}); each call is one short transaction judged
 * by database time. Only an event whose earlier aggregate versions are all SENT can be leased, so one asset's events
 * reach the broker in version order.
 */
public interface OutboxEvents {
    /** Leases the oldest deliverable event: PENDING and due, or LEASED with an expired lease. */
    Optional<OutboxDelivery> claim(Duration leaseDuration);

    /**
     * Records the broker's acknowledgement and clears the last failure. False means another lease took the event
     * over; nothing changed.
     */
    boolean sent(OutboxDelivery delivery);

    /**
     * Returns a failed delivery to PENDING after an exponential delay (one second doubled per attempt, at most
     * {@code maxDelay}) and records why it failed. False means the lease is no longer current; nothing changed.
     */
    boolean retry(OutboxDelivery delivery, DeliveryFailure failure, Duration maxDelay);

    /** Returns an unfinished delivery to PENDING, due at once (shutdown, not a failure). False as for retry. */
    boolean release(OutboxDelivery delivery);
}
