package com.libra.streaming.recommendation.ingestion.domain;

import com.libra.streaming.recommendation.freshness.domain.InputTopic;
import java.util.UUID;

/** A Core event that passed the V1 schema and every semantic rule of its topic. */
public sealed interface CoreEvent permits CatalogChange, ProfileChange, PlaybackProgress {
    Envelope envelope();

    InputTopic topic();

    default UUID eventId() { return envelope().eventId(); }
}
