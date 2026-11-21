package com.libra.streaming.recommendation.profiles.application;

import java.util.UUID;

/**
 * Removes a deleted profile's personalization and profile links in the tombstone's transaction. Each feature that
 * keeps per-profile data contributes one; aggregate history that is not personal data stays (RA01.1).
 */
public interface ProfileErasure {
    void erase(UUID profileId);
}
