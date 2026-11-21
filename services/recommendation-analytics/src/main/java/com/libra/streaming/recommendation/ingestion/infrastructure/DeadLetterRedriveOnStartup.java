package com.libra.streaming.recommendation.ingestion.infrastructure;

import com.libra.streaming.recommendation.ingestion.application.DeadLetterRedrive;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "libra.analytics.dead-letters", name = "redrive-on-startup", havingValue = "true")
public class DeadLetterRedriveOnStartup {
    private static final Logger LOG = LoggerFactory.getLogger(DeadLetterRedriveOnStartup.class);
    private final DeadLetterRedrive redrive;

    public DeadLetterRedriveOnStartup(DeadLetterRedrive redrive) { this.redrive = redrive; }

    /** On its own thread: a long pass must not hold back readiness (ApplicationReadyEvent listeners run inline). */
    @EventListener(ApplicationReadyEvent.class)
    public void ready() {
        var worker = new Thread(this::pass, "analytics-dead-letter-redrive");
        worker.setDaemon(true);
        worker.start();
    }

    void pass() {
        try {
            var report = redrive.redriveHeld(100);
            LOG.info("Dead-letter redrive: redriven={}, stillHeld={}", report.redriven(), report.stillHeld());
        } catch (RuntimeException failure) { LOG.warn("Dead-letter startup redrive failed"); }
    }
}
