package com.libra.streaming.media.lifecycle.application;

import com.libra.streaming.media.lifecycle.domain.CleanupPlan;
import java.util.Objects;

/**
 * Deletes what one settled upload left behind: its staging object, the frozen source of every attempt but the
 * selected one, and the output prefix of every attempt but the selected one. The selected source and output are
 * never named, let alone deleted. Deletion is idempotent, so an interrupted pass is simply repeated.
 *
 * <p>A pass that deleted anything does not finish the upload: an admitted write can still land after a deadline
 * (M12), so the upload is recorded as cleaned only by a later pass that finds nothing left.
 */
public final class ObjectCleanup {
    private final CleanupStorage storage;
    private final CleanupPlans plans;
    private final LifecycleEvents events;
    private final int maxObjectsPerPrefix;

    public ObjectCleanup(CleanupStorage storage, CleanupPlans plans, LifecycleEvents events, int maxObjectsPerPrefix) {
        this.storage = Objects.requireNonNull(storage);
        this.plans = Objects.requireNonNull(plans);
        this.events = Objects.requireNonNull(events);
        if (maxObjectsPerPrefix < 1 || maxObjectsPerPrefix > 10_000) {
            throw new IllegalArgumentException("Invalid cleanup batch");
        }
        this.maxObjectsPerPrefix = maxObjectsPerPrefix;
    }

    /** Returns the number of objects this pass found and deleted; zero means the upload is now recorded as cleaned. */
    public int clean(CleanupPlan plan) {
        requireRecordedLayout(plan);
        int found = storage.deleteStaging(plan.stagingKey()) ? 1 : 0;
        boolean remaining = false;
        for (int attempt = 1; attempt <= plan.attempts(); attempt++) {
            String source = storage.sourceKey(plan.uploadId(), attempt);
            if (!source.equals(plan.selectedSourceKey()) && storage.deleteSource(source)) { found++; }
            String prefix = storage.outputPrefix(plan.uploadId(), attempt);
            if (overlaps(prefix, plan.selectedOutputPrefix())) { continue; }
            var batch = storage.deleteOutput(prefix, maxObjectsPerPrefix);
            found += batch.deleted();
            remaining |= !batch.exhausted();
        }
        if (found > 0) { events.deleted(found); }
        if (found == 0 && !remaining && plans.markCleaned(plan.uploadId())) { events.cleaned(); }
        return remaining ? Math.max(found, 1) : found;
    }

    /**
     * Only the selection is recorded as keys; every other key is named from today's layout. A selection today's layout
     * does not name means the layout changed (M32): the derived keys point elsewhere, so a pass would find nothing and
     * record an upload whose objects remain. Nothing is deleted or recorded for such a plan. A plan without a selection
     * cannot show a change; the layout check at startup (V9) is what keeps those keys where the attempts wrote them.
     */
    private void requireRecordedLayout(CleanupPlan plan) {
        boolean source = plan.selectedSourceKey() == null;
        boolean output = plan.selectedOutputPrefix() == null;
        for (int attempt = 1; attempt <= plan.attempts(); attempt++) {
            source |= storage.sourceKey(plan.uploadId(), attempt).equals(plan.selectedSourceKey());
            output |= storage.outputPrefix(plan.uploadId(), attempt).equals(plan.selectedOutputPrefix());
        }
        if (!source || !output) { throw new LayoutChanged(); }
    }

    /** The plan's selection is not where today's layout names its keys; deliberately carries no key. */
    public static final class LayoutChanged extends RuntimeException {
        LayoutChanged() { super("CLEANUP_LAYOUT_CHANGED", null, false, false); }
    }

    /**
     * The selected output, or a prefix containing it or contained in it, is never deleted. Defensive since M32: a plan
     * reaches this only when its selection equals one attempt prefix.
     */
    private static boolean overlaps(String prefix, String selected) {
        return selected != null && (prefix.equals(selected) || prefix.startsWith(selected + "/")
                || selected.startsWith(prefix + "/"));
    }
}
