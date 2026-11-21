package com.libra.streaming.recommendation.ingestion.application;

import com.libra.streaming.recommendation.ingestion.domain.CoreEvent;

/**
 * Observation points around the intake transaction. Production uses {@link #NONE}; the RA02.5 fault-injection
 * suites throw from them to crash before commit, after commit and before the offset is acknowledged.
 */
public interface IntakeProbe {
    IntakeProbe NONE = new IntakeProbe() {};

    /** Inside the transaction, after the projection changed and before commit. */
    default void beforeCommit(CoreEvent event) {}

    /** After the commit, before control returns to the consumer (and so before the offset is acknowledged). */
    default void afterCommit(CoreEvent event, EventIntake.Outcome outcome) {}
}
