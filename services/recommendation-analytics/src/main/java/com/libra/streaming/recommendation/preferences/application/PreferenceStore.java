package com.libra.streaming.recommendation.preferences.application;

import com.libra.streaming.recommendation.preferences.domain.WatchedItem;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Per-profile watched time per item and UTC day. Writes join the caller's transaction; a read is one statement. */
public interface PreferenceStore {
    /**
     * Adds accepted watched time. The row keeps the account of its first writer; time arriving for the same profile
     * under another account is not added (Core never moves a profile between accounts).
     */
    void add(UUID profileId, UUID accountId, UUID contentId, LocalDate day, long acceptedWatchedMs);

    /**
     * The profile's watched items with time on {@code fromDay} or later, summed over those days, each resolved to its
     * current movie or series and the genres of that title. Empty for a profile of another account.
     */
    List<WatchedItem> watched(UUID accountId, UUID profileId, LocalDate fromDay);
}
