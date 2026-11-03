package com.libra.streaming.media.events.domain;

/** Why the broker did not acknowledge an event; recorded on the event until it is sent. */
public enum DeliveryFailure {
    /** No acknowledgement in time, or a transient broker or network error. */
    BROKER_UNAVAILABLE,
    /** The broker or producer refused the record (authorization, a fatal producer state, an invalid request). */
    BROKER_REJECTED
}
