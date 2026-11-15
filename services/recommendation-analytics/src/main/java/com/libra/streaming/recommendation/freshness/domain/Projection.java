package com.libra.streaming.recommendation.freshness.domain;

import java.util.EnumSet;
import java.util.Set;

/** An answer Analytics serves, with the input topics its freshness depends on. */
public enum Projection {
    STATISTICS(EnumSet.of(InputTopic.PLAYBACK)),
    RECOMMENDATIONS(EnumSet.allOf(InputTopic.class));

    private final Set<InputTopic> inputs;

    Projection(Set<InputTopic> inputs) { this.inputs = Set.copyOf(inputs); }

    public Set<InputTopic> inputs() { return inputs; }
}
