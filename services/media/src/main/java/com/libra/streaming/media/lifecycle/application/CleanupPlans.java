package com.libra.streaming.media.lifecycle.application;

import com.libra.streaming.media.lifecycle.domain.CleanupPlan;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/** Which uploads are settled, and the record that their leftover objects are gone. */
public interface CleanupPlans {
    /**
     * At most {@code limit} uploads not yet cleaned that settled at least {@code writeGrace} ago: expired uploads past
     * their deadline, and submitted uploads whose job is terminal, measured from the later of the upload deadline and
     * the terminal transition. The grace covers writes admitted before the settlement that are still in flight.
     * Taking a batch moves it behind every upload not yet taken, so an upload that keeps failing cannot hold the
     * front of the queue.
     */
    List<CleanupPlan> due(Duration writeGrace, int limit);

    /** Records that a pass found nothing left to delete. False if another pass already recorded it. */
    boolean markCleaned(UUID uploadId);
}
