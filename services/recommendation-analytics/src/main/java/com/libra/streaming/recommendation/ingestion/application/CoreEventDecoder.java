package com.libra.streaming.recommendation.ingestion.application;

import com.libra.streaming.recommendation.freshness.domain.InputTopic;
import com.libra.streaming.recommendation.ingestion.domain.CoreEvent;
import com.libra.streaming.recommendation.ingestion.domain.EventRejection;

/** Turns a raw record of a Core topic into a validated event. */
public interface CoreEventDecoder {
    /**
     * @param key the Kafka record key (the aggregate ID Core published with)
     * @throws EventRejection when the record violates the V1 schema or a semantic rule; never for transient reasons
     */
    CoreEvent decode(InputTopic topic, String key, String value);
}
