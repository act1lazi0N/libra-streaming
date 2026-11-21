package com.libra.streaming.recommendation.bootstrap.application;

import com.libra.streaming.recommendation.bootstrap.domain.BootstrapSource;
import java.time.Instant;
import java.util.Optional;

/** Durable position of a bootstrap generation per source. Joins the caller's transaction. */
public interface BootstrapProgress {
    /**
     * @param cursor the {@code next} value of the last page fully applied; null before the first page
     * @param applied items applied (or found already applied) so far
     * @param rejected items that could never become data (counted, not parked; see the RA02.4 document)
     */
    record Position(String cursor, long applied, long rejected, boolean completed) {}

    /** Creates the row on first use. */
    Position start(long generation, BootstrapSource source, Instant at);

    /** Moves the position forward after a page; {@code completed} once the source is exhausted. */
    void advance(long generation, BootstrapSource source, String cursor, long applied, long rejected,
            boolean completed, Instant at);

    Optional<Position> find(long generation, BootstrapSource source);
}
