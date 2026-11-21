package com.libra.streaming.recommendation.ingestion.application;

import com.libra.streaming.recommendation.ingestion.domain.CoreEvent;

/** Durable receipts of applied events. Joins the caller's transaction. */
public interface ProcessedEvents {
    enum Receipt {
        /** First time this event ID is seen: the caller applies it in the same transaction. */
        NEW,
        /** Already applied with the same identity: nothing to do. */
        DUPLICATE,
        /** The event ID was applied for a different topic, type or aggregate version. */
        CONFLICT
    }

    Receipt record(CoreEvent event);
}
