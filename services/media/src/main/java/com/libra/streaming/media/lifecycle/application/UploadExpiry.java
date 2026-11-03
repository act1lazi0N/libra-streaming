package com.libra.streaming.media.lifecycle.application;

/** Durable expiry of abandoned uploads. */
public interface UploadExpiry {
    /**
     * Expires at most {@code limit} uploads still OPEN at their deadline, in one transaction: each becomes EXPIRED and
     * its asset FAILED with the FAILED event. Returns how many were expired.
     */
    int expire(int limit);
}
