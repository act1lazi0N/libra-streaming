package com.libra.streaming.recommendation.profiles.domain;

import java.time.Instant;
import java.util.UUID;

/** The latest known lifecycle state of a profile; {@code deleted} is terminal. */
public record ProfileState(UUID profileId, UUID accountId, long version, boolean deleted, Instant changedAt) {}
