package com.libra.streaming.recommendation.ingestion.application;

import com.libra.streaming.recommendation.catalog.application.CatalogProjection;
import com.libra.streaming.recommendation.freshness.domain.InputTopic;
import com.libra.streaming.recommendation.ingestion.domain.CatalogChange;
import com.libra.streaming.recommendation.ingestion.domain.CoreEvent;
import com.libra.streaming.recommendation.ingestion.domain.EventRejection;
import com.libra.streaming.recommendation.ingestion.domain.PlaybackProgress;
import com.libra.streaming.recommendation.ingestion.domain.ProfileChange;
import com.libra.streaming.recommendation.playback.application.PlaybackProjection;
import com.libra.streaming.recommendation.profiles.application.ProfileProjection;

/**
 * The single path by which a Core event becomes Analytics data, whatever delivered it (live Kafka, dead-letter
 * redrive or bootstrap replay): validate, then record the receipt and apply the projection in one transaction.
 * The caller acknowledges the record only after this method returns, so an acknowledged record is always committed.
 */
public class EventIntake {
    public enum Outcome { APPLIED, DUPLICATE }

    private final CoreEventDecoder decoder;
    private final ProcessedEvents receipts;
    private final Transactions transactions;
    private final CatalogProjection catalog;
    private final ProfileProjection profiles;
    private final PlaybackProjection playback;
    private final IntakeProbe probe;

    public EventIntake(CoreEventDecoder decoder, ProcessedEvents receipts, Transactions transactions,
            CatalogProjection catalog, ProfileProjection profiles, PlaybackProjection playback, IntakeProbe probe) {
        this.decoder = decoder;
        this.receipts = receipts;
        this.transactions = transactions;
        this.catalog = catalog;
        this.profiles = profiles;
        this.playback = playback;
        this.probe = probe;
    }

    /** @throws EventRejection for a record that must be parked; any other exception leaves it to be retried */
    public Outcome accept(InputTopic topic, String key, String value) {
        CoreEvent event = decoder.decode(topic, key, value);
        Outcome outcome = transactions.inTransaction(() -> switch (receipts.record(event)) {
            case DUPLICATE -> Outcome.DUPLICATE;
            case CONFLICT -> throw EventRejection.conflict("EVENT_ID_REUSED");
            case NEW -> {
                apply(event);
                probe.beforeCommit(event);
                yield Outcome.APPLIED;
            }
        });
        probe.afterCommit(event, outcome);
        return outcome;
    }

    private void apply(CoreEvent event) {
        switch (event) {
            case CatalogChange change -> catalog.apply(change);
            case ProfileChange change -> profiles.apply(change);
            case PlaybackProgress progress -> playback.apply(progress);
        }
    }
}
