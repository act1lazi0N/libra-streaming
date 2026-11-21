package com.libra.streaming.recommendation.bootstrap.application;

import com.libra.streaming.recommendation.bootstrap.domain.BootstrapSource;
import com.libra.streaming.recommendation.freshness.application.BackfillProgress;
import com.libra.streaming.recommendation.freshness.domain.InputTopic;

/**
 * With the bootstrap enabled, a topic's checkpoint waits until the configured generation has completed its source:
 * before that, the history Kafka no longer holds is still missing. With it disabled, the Kafka log is the whole
 * history (the operator's choice, documented in RA02.4 and RA04.2).
 */
public class BootstrapBackfill implements BackfillProgress {
    private final BootstrapProgress progress;
    private final boolean enabled;
    private final long generation;

    public BootstrapBackfill(BootstrapProgress progress, boolean enabled, long generation) {
        this.progress = progress;
        this.enabled = enabled;
        this.generation = generation;
    }

    @Override
    public boolean complete(InputTopic topic) {
        if (!enabled) { return true; }
        return progress.find(generation, source(topic)).map(BootstrapProgress.Position::completed).orElse(false);
    }

    static BootstrapSource source(InputTopic topic) {
        return switch (topic) {
            case CATALOG -> BootstrapSource.CATALOG;
            case PROFILES -> BootstrapSource.PROFILES;
            case PLAYBACK -> BootstrapSource.PLAYBACK;
        };
    }
}
