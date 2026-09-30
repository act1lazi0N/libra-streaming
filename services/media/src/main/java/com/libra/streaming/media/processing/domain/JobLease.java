package com.libra.streaming.media.processing.domain;

import java.time.Instant;
import java.util.UUID;

/** Attempt and token together identify the only writer admitted by PostgreSQL. */
public record JobLease(UUID jobId, UUID uploadId, UUID assetId, long assetVersion,
        int attempt, UUID token, Instant expiresAt) {}
