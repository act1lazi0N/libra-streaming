package com.libra.streaming.recommendation.ingestion.domain;

/**
 * A record that can never become a fact: retrying it gives the same answer, so it is parked, not retried. The message
 * is a fixed code only. It never carries payload text, identifiers or parser messages, because Kafka error handling
 * and the dead-letter store may log or persist it.
 */
public final class EventRejection extends RuntimeException {
    public enum Reason {
        /** Violates the V1 JSON Schema (envelope, closed payload, formats, bounds). */
        SCHEMA_INVALID,
        /** Schema-valid but breaks a rule the schema cannot express (RA01.1 semantic table). */
        SEMANTIC_INVALID,
        /** Contradicts a fact already stored: a reused event ID, a second event for one sequence, a changed identity. */
        EVENT_CONFLICT
    }

    private final Reason reason;
    private final String code;

    private EventRejection(Reason reason, String code) {
        super(reason + ":" + code, null, false, false);
        this.reason = reason;
        this.code = code;
    }

    public static EventRejection schema(String code) { return new EventRejection(Reason.SCHEMA_INVALID, code); }

    public static EventRejection semantic(String code) { return new EventRejection(Reason.SEMANTIC_INVALID, code); }

    public static EventRejection conflict(String code) { return new EventRejection(Reason.EVENT_CONFLICT, code); }

    public Reason reason() { return reason; }

    /** A fixed, payload-free code such as {@code PAYLOAD_FIELD} or {@code SEQUENCE_CONFLICT}. */
    public String code() { return code; }
}
